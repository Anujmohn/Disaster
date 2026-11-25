import { Capacitor, registerPlugin } from '@capacitor/core';

export interface NativeStatusResult {
  status: string;
  platform: string;
  androidVersion: string;
  sdkInt: number;
  deviceModel: string;
  message: string;
}

interface NativeBridgeTestPluginInterface {
  getNativeStatus(): Promise<NativeStatusResult>;
}

// Register native plugin for Capacitor
const NativeBridgeTest = registerPlugin<NativeBridgeTestPluginInterface>('NativeBridgeTest');

export interface BridgeCheckResult {
  isNative: boolean;
  platform: string;
  data: NativeStatusResult | null;
}

/**
 * Checks Capacitor native bridge status.
 * Returns verified native system info when running inside Android container,
 * or clean fallback when running in a normal web browser.
 */
export async function checkNativeBridgeStatus(): Promise<BridgeCheckResult> {
  if (Capacitor.isNativePlatform()) {
    try {
      const data = await NativeBridgeTest.getNativeStatus();
      return {
        isNative: true,
        platform: Capacitor.getPlatform(),
        data,
      };
    } catch (err) {
      console.warn('Native bridge call error:', err);
      return {
        isNative: true,
        platform: Capacitor.getPlatform(),
        data: {
          status: 'error',
          platform: 'Android',
          androidVersion: 'unknown',
          sdkInt: 0,
          deviceModel: 'unknown',
          message: err instanceof Error ? err.message : String(err),
        },
      };
    }
  }

  // Clean fallback for browser environment
  return {
    isNative: false,
    platform: 'web',
    data: null,
  };
}
