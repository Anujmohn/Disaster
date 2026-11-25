import React, { useEffect, useState } from 'react';
import { Capacitor } from '@capacitor/core';
import ChatScreen from './screens/ChatScreen';
import MobileChatScreen from './screens/MobileChatScreen';
import LocationScreen from './screens/LocationScreen';
import MapScreen from './screens/MapScreen';

type Tab = 'map' | 'location' | 'chat';
type BackendStatus = 'checking' | 'connected' | 'disconnected';

export default function App(): React.JSX.Element {
  const isNative = Capacitor.isNativePlatform();
  const [activeTab, setActiveTab] = useState<Tab>('map');
  const [backendStatus, setBackendStatus] = useState<BackendStatus>('checking');

  useEffect(() => {
    let cancelled = false;

    async function checkHealth() {
      const endpoints = ['/health', 'http://localhost:8000/health'];
      for (const url of endpoints) {
        try {
          const res = await fetch(url);
          if (!cancelled) {
            setBackendStatus(res.ok ? 'connected' : 'disconnected');
          }
          return;
        } catch {
          // Try direct localhost fallback if proxy path is unavailable.
        }
      }
      if (!cancelled) {
        setBackendStatus('disconnected');
      }
    }

    checkHealth();
    return () => {
      cancelled = true;
    };
  }, []);

  if (isNative) {
    if (activeTab === 'chat') {
      return (
        <div id="root" className="apk-root">
          <MobileChatScreen onBack={() => setActiveTab('map')} />
        </div>
      );
    }

    return (
      <div id="root" className="apk-root">
        <header className="apk-top-bar">
          <span className="apk-top-title">Disaster Safety</span>
          <span className={`apk-status-pill ${backendStatus}`}>
            {backendStatus === 'connected' ? 'Live' : backendStatus === 'checking' ? 'Connecting…' : 'Offline'}
          </span>
        </header>

        <main className="apk-main">
          {activeTab === 'map' ? <MapScreen /> : <LocationScreen />}
        </main>

        <nav className="apk-bottom-nav" role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={activeTab === 'map'}
            className={`apk-nav-btn ${activeTab === 'map' ? 'active' : ''}`}
            onClick={() => setActiveTab('map')}
          >
            Map
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={activeTab === 'location'}
            className={`apk-nav-btn ${activeTab === 'location' ? 'active' : ''}`}
            onClick={() => setActiveTab('location')}
          >
            My Location
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={false}
            className="apk-nav-btn"
            onClick={() => setActiveTab('chat')}
          >
            Nearby Chat
          </button>
        </nav>
      </div>
    );
  }

  return (
    <div id="root">
      <header className="app-header">
        <div className="brand-title">
          <span>🛡️</span>
          <span>Disaster Safety</span>
          <span
            className="badge"
            style={{
              fontSize: '0.72rem',
              backgroundColor: backendStatus === 'connected' ? '#DCFCE7' : backendStatus === 'checking' ? '#FEF9C3' : '#FEE2E2',
              color: backendStatus === 'connected' ? '#166534' : backendStatus === 'checking' ? '#854D0E' : '#991B1B',
              marginLeft: '8px',
            }}
          >
            {backendStatus === 'connected' ? '● Live Updates' : backendStatus === 'checking' ? '○ Connecting…' : '✕ Offline Mode'}
          </span>
        </div>

        <nav className="nav-tabs" role="tablist">
          <button
            role="tab"
            aria-selected={activeTab === 'map'}
            className={`nav-tab-btn ${activeTab === 'map' ? 'active' : ''}`}
            onClick={() => setActiveTab('map')}
          >
            🗺️ Safety Map
          </button>
          <button
            role="tab"
            aria-selected={activeTab === 'location'}
            className={`nav-tab-btn ${activeTab === 'location' ? 'active' : ''}`}
            onClick={() => setActiveTab('location')}
          >
            📍 My Location
          </button>
          <button
            role="tab"
            aria-selected={activeTab === 'chat'}
            className={`nav-tab-btn ${activeTab === 'chat' ? 'active' : ''}`}
            onClick={() => setActiveTab('chat')}
          >
            💬 Nearby Chat
          </button>
        </nav>
      </header>

      <main className="screen-container">
        {activeTab === 'map' ? <MapScreen /> : activeTab === 'location' ? <LocationScreen /> : <ChatScreen />}
      </main>
    </div>
  );
}
