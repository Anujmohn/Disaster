import { DisasterEventSummary } from '../api/disasterApi';

export function getDisasterCoords(ev: DisasterEventSummary): [number, number] | null {
  if (ev.location?.coordinates) {
    return [ev.location.coordinates[0], ev.location.coordinates[1]];
  }
  if (ev.affected_area?.coordinates) {
    const coords = ev.affected_area.coordinates as unknown as number[][][][];
    if (coords[0]?.[0]?.[0]) {
      return [coords[0][0][0][0], coords[0][0][0][1]];
    }
    const ring = ev.affected_area.coordinates as unknown as number[][][];
    if (ring[0]?.[0]) {
      return [ring[0][0][0], ring[0][0][1]];
    }
  }
  return null;
}

export function computePredictedFloodPath(disaster: DisasterEventSummary): [number, number][] {
  if (
    disaster.flow_path?.coordinates &&
    Array.isArray(disaster.flow_path.coordinates) &&
    disaster.flow_path.coordinates.length >= 2
  ) {
    return disaster.flow_path.coordinates;
  }

  const coords = getDisasterCoords(disaster);
  if (!coords) return [];

  const [lon, lat] = coords;
  let dx = 0.08;
  let dy = -0.035;

  if (lat > 24.0 && lon >= 88.0) {
    dx = -0.06;
    dy = -0.05;
  } else if (lat > 24.0) {
    dx = 0.08;
    dy = -0.035;
  } else if (lon < 75.0) {
    dx = -0.07;
    dy = -0.02;
  } else {
    dx = 0.08;
    dy = -0.03;
  }

  const pts: [number, number][] = [];
  let currX = lon - dx * 1.2;
  let currY = lat - dy * 1.2;

  for (let i = 0; i < 7; i++) {
    const meander = i % 2 === 1 ? 0.015 : -0.015;
    pts.push([
      parseFloat(currX.toFixed(5)),
      parseFloat((currY + (Math.abs(dx) > Math.abs(dy) ? meander : 0)).toFixed(5)),
    ]);
    currX += dx;
    currY += dy;
  }

  return pts;
}

export function getBasinName(coords: [number, number] | null): string {
  if (!coords) return 'Indo-Gangetic Basin';
  const [lon, lat] = coords;
  if (lat > 24.0 && lon >= 88.0) return 'Brahmaputra River Basin (Assam/Meghalaya)';
  if (lat > 24.0) return 'Ganges–Yamuna Drainage Basin (Northern Plains)';
  if (lon < 75.0) return 'Narmada–Tapi Western Basin (Arabian Sea)';
  return 'Peninsular East-Flowing Basin (Mahanadi/Godavari/Krishna)';
}

export function getFlowDirectionText(coords: [number, number] | null): string {
  if (!coords) return 'East-Southeast (~115°)';
  const [lon, lat] = coords;
  if (lat > 24.0 && lon >= 88.0) return 'West-Southwest curving South (~220°)';
  if (lat > 24.0) return 'East-Southeast (~115°)';
  if (lon < 75.0) return 'West-Southwest (~245°)';
  return 'East-Southeast (~120°)';
}
