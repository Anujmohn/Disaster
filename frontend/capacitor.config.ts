import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.disastersafety.app',
  appName: 'Disaster Safety',
  webDir: 'dist',
  server: {
    androidScheme: 'https',
  },
};

export default config;
