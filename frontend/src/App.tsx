import React, { useState, useEffect } from 'react';
import { Capacitor } from '@capacitor/core';
import ChatScreen from './screens/ChatScreen';
import MobileChatScreen from './screens/MobileChatScreen';
import LocationScreen from './screens/LocationScreen';
import MapScreen from './screens/MapScreen';

type Tab = 'map' | 'location' | 'chat';

export default function App(): React.JSX.Element {
  const isNative = Capacitor.isNativePlatform();
  const [activeTab, setActiveTab] = useState<Tab>('map');
  const [backendStatus, setBackendStatus] = useState<'checking' | 'connected' | 'disconnected'>('checking');

  useEffect(() => {
    // Check backend health
    fetch('/health')
      .then((res) => {
        if (res.ok) setBackendStatus('connected');
        else setBackendStatus('disconnected');
      })
      .catch(() => {
        // Fallback check direct localhost:8000
        fetch('http://localhost:8000/health')
          .then((res) => {
            if (res.ok) setBackendStatus('connected');
            else setBackendStatus('disconnected');
          })
          .catch(() => setBackendStatus('disconnected'));
      });
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
            {backendStatus === 'connected' ? 'Online' : backendStatus === 'checking' ? 'Checking' : 'Offline'}
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
            Location
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={false}
            className="apk-nav-btn"
            onClick={() => setActiveTab('chat')}
          >
            Offline Chat
          </button>
        </nav>
      </div>
    );
  }

  return (
    <div id="root">
      {/* Main Web Application Header */}
      <header className="app-header">
        <div className="brand-title">
          <span>🛡️</span>
          <span>Disaster Safety Platform</span>
          <span
            className="badge"
            style={{
              fontSize: '0.7rem',
              backgroundColor: backendStatus === 'connected' ? '#DCFCE7' : backendStatus === 'checking' ? '#FEF9C3' : '#FEE2E2',
              color: backendStatus === 'connected' ? '#166534' : backendStatus === 'checking' ? '#854D0E' : '#991B1B',
              marginLeft: '8px',
            }}
          >
            {backendStatus === 'connected' ? '● Backend Live' : backendStatus === 'checking' ? '○ Connecting...' : '✕ Offline'}
          </span>
        </div>

        {/* Tab Navigation */}
        <nav className="nav-tabs" role="tablist">
          <button
            role="tab"
            aria-selected={activeTab === 'map'}
            className={`nav-tab-btn ${activeTab === 'map' ? 'active' : ''}`}
            onClick={() => setActiveTab('map')}
          >
            🗺️ Disaster Map
          </button>
          <button
            role="tab"
            aria-selected={activeTab === 'location'}
            className={`nav-tab-btn ${activeTab === 'location' ? 'active' : ''}`}
            onClick={() => setActiveTab('location')}
          >
            📍 GPS Location
          </button>
          <button
            role="tab"
            aria-selected={activeTab === 'chat'}
            className={`nav-tab-btn ${activeTab === 'chat' ? 'active' : ''}`}
            onClick={() => setActiveTab('chat')}
          >
            💬 Offline Chat
          </button>
        </nav>
      </header>

      {/* Screen Container */}
      <main className="screen-container">
        {activeTab === 'map' ? <MapScreen /> : activeTab === 'location' ? <LocationScreen /> : <ChatScreen />}
      </main>
    </div>
  );
}
