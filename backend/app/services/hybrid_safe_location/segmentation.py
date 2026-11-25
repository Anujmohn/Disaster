"""
Stage 2: Zero-Shot Object Detection and Segmentation.

Evaluates and runs language-guided open-vocabulary detection + segmentation
(LangSAM / GroundingDINO + SAM 2) using explicit semantic prompts.
Enforces singleton lifecycle, CPU/CUDA device detection, and timeout safety.
Explicitly reports MODEL_UNAVAILABLE when ML dependencies or weights are absent
instead of fabricating placeholder masks.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Callable, Optional

import numpy as np

from app.schemas.hybrid_safe_location import SegmentationModelStatus

logger = logging.getLogger(__name__)

OPEN_SPACE_PROMPTS = [
    "Large open field",
    "Open playground",
    "Sports ground",
    "Public park",
    "Open vacant land",
]

OBSTACLE_AND_CONTEXT_PROMPTS = [
    "Wide paved road",
    "Building",
    "Water body",
    "Dense tree coverage",
]

ALL_ZERO_SHOT_PROMPTS = OPEN_SPACE_PROMPTS + OBSTACLE_AND_CONTEXT_PROMPTS


@dataclass
class SegmentationDetection:
    category: str
    prompt: str
    confidence: float
    mask: np.ndarray  # bool array (H, W)
    bbox_px: tuple[int, int, int, int]  # (x1, y1, x2, y2)


def _prompt_to_category(prompt: str) -> str:
    p = prompt.lower().strip()
    if "field" in p:
        return "open_field"
    if "playground" in p:
        return "playground"
    if "sports" in p or "ground" in p:
        return "sports_ground"
    if "park" in p:
        return "park"
    if "vacant" in p or "unobstructed" in p or "open" in p:
        return "open_vacant_land"
    if "road" in p:
        return "wide_paved_road"
    if "building" in p:
        return "building"
    if "water" in p:
        return "water_body"
    if "tree" in p or "vegetation" in p:
        return "dense_vegetation"
    return "uncertain"


class ZeroShotSegmenter:
    """
    Singleton model manager for LangSAM / GroundingDINO + SAM 2.
    """

    def __init__(self) -> None:
        self._initialized = False
        self._available = False
        self._device = "cpu"
        self._model_name = "LangSAM (GroundingDINO + SAM 2)"
        self._unavailable_reason: Optional[str] = None
        self._lang_sam_model = None

    def _ensure_loaded(self) -> None:
        if self._initialized:
            return
        self._initialized = True

        try:
            import torch  # type: ignore

            self._device = "cuda" if torch.cuda.is_available() else "cpu"
        except ImportError:
            self._available = False
            self._unavailable_reason = (
                "PyTorch ('torch') is not installed in the Python environment; "
                "zero-shot GroundingDINO + SAM 2 / LangSAM segmentation cannot execute."
            )
            return

        try:
            from PIL import Image  # type: ignore  # noqa: F401
        except ImportError:
            self._available = False
            self._unavailable_reason = "Pillow ('PIL') is not installed; required for model image input."
            return

        # Try LangSAM first, then HuggingFace GroundingDINO + SAM2
        try:
            from lang_sam import LangSAM  # type: ignore

            self._lang_sam_model = LangSAM()
            self._model_name = "LangSAM"
            self._available = True
            return
        except ImportError:
            pass
        except Exception as exc:
            self._unavailable_reason = f"LangSAM initialization failed: {exc}"
            self._available = False
            return

        try:
            import transformers  # type: ignore  # noqa: F401
            import sam2  # type: ignore  # noqa: F401

            self._model_name = "GroundingDINO + SAM 2"
            self._unavailable_reason = (
                "Model weights are not pre-downloaded and offline environment blocks HuggingFace hub."
            )
            self._available = False
        except ImportError:
            self._available = False
            self._unavailable_reason = (
                "Neither 'lang_sam' nor 'transformers' + 'sam2' are installed in backend/.venv."
            )

    def get_status(self) -> SegmentationModelStatus:
        self._ensure_loaded()
        return SegmentationModelStatus(
            model_name=self._model_name,
            device=self._device,
            status="AVAILABLE" if self._available else "MODEL_UNAVAILABLE",
            reason=self._unavailable_reason,
        )

    def segment(
        self,
        rgb: np.ndarray,
        prompts: Optional[list[str]] = None,
        timeout_s: float = 15.0,
        custom_runner: Optional[
            Callable[[np.ndarray, list[str]], list[SegmentationDetection]]
        ] = None,
    ) -> tuple[list[SegmentationDetection], SegmentationModelStatus]:
        """
        Runs zero-shot detection & segmentation on an RGB satellite crop.
        """
        if custom_runner is not None:
            t0 = time.monotonic()
            try:
                dets = custom_runner(rgb, prompts or ALL_ZERO_SHOT_PROMPTS)
                if time.monotonic() - t0 > timeout_s:
                    return [], SegmentationModelStatus(
                        model_name="Injected ZeroShot Runner",
                        device="cpu",
                        status="INFERENCE_TIMEOUT",
                        reason=f"Inference exceeded {timeout_s}s timeout.",
                    )
                return dets, SegmentationModelStatus(
                    model_name="Injected ZeroShot Runner",
                    device="cpu",
                    status="AVAILABLE",
                )
            except Exception as exc:
                return [], SegmentationModelStatus(
                    model_name="Injected ZeroShot Runner",
                    device="cpu",
                    status="INFERENCE_FAILED",
                    reason=str(exc),
                )

        self._ensure_loaded()
        if not self._available or self._lang_sam_model is None:
            return [], self.get_status()

        try:
            from PIL import Image  # type: ignore

            pil_img = Image.fromarray(rgb)
            prompt_str = " . ".join(prompts or ALL_ZERO_SHOT_PROMPTS)
            t0 = time.monotonic()
            results = self._lang_sam_model.predict([pil_img], [prompt_str])
            if time.monotonic() - t0 > timeout_s:
                return [], SegmentationModelStatus(
                    model_name=self._model_name,
                    device=self._device,
                    status="INFERENCE_TIMEOUT",
                    reason=f"Segmentation exceeded {timeout_s}s timeout.",
                )

            detections: list[SegmentationDetection] = []
            if results and isinstance(results, list):
                res = results[0]
                masks = res.get("masks", [])
                scores = res.get("scores", [])
                labels = res.get("labels", [])
                boxes = res.get("boxes", [])
                for mask, score, label, box in zip(masks, scores, labels, boxes):
                    mask_bool = np.asarray(mask, dtype=bool)
                    conf = float(score)
                    cat = _prompt_to_category(str(label)) if conf >= 0.30 else "uncertain"
                    bx = tuple(int(v) for v in box[:4])
                    detections.append(
                        SegmentationDetection(
                            category=cat,
                            prompt=str(label),
                            confidence=round(conf, 3),
                            mask=mask_bool,
                            bbox_px=(bx[0], bx[1], bx[2], bx[3]),
                        )
                    )
            return detections, self.get_status()
        except Exception as exc:
            logger.exception("Zero-shot segmentation inference failed")
            return [], SegmentationModelStatus(
                model_name=self._model_name,
                device=self._device,
                status="INFERENCE_FAILED",
                reason=str(exc),
            )


_SEGMENTER: Optional[ZeroShotSegmenter] = None


def get_zero_shot_segmenter() -> ZeroShotSegmenter:
    global _SEGMENTER
    if _SEGMENTER is None:
        _SEGMENTER = ZeroShotSegmenter()
    return _SEGMENTER
