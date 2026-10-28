# 🎬 Stream (Android Media Streaming Engine)

<p align="center">
   <img width="1920" height="1080" alt="image" src="https://github.com/user-attachments/assets/0b666769-9f37-43c6-9774-5b9d134ce9ac" />
   </p>


A production-grade, high-performance Android media streaming application engineered with modern Android architecture, custom multi-provider resolution pipelines, and an adaptive video player.

---

## 🚀 Key Features

* **Declarative Multi-Provider Engine:** Dynamic provider registry supporting automated server failover, latency scoring, and runtime reliability tracking.
* **Headless WebView Resolution Engine:** Automated sub-iframe resolution, cross-candidate generation isolation, and header-based origin/referer preservation to bypass modern CDN blocks and bot-checks.
* **Advanced Media Playback:** Powered by **AndroidX Media3 (ExoPlayer)** supporting adaptive HLS/DASH streaming, multi-track subtitle selection, dynamic playback controls, and automated intro/credit segment skipping.
* **Robust ID Bridging:** Seamless metadata synchronization mapping **TMDB IDs** to **AniList / MAL IDs** for unified movie, TV series, and anime playback.
* **Modern Android Stack:** Built 100% with **Jetpack Compose**, Kotlin Coroutines, StateFlow, and Clean Architecture principles.

---

## 🛠️ Architecture & Tech Stack

* **UI:** Jetpack Compose, Material 3, ConstraintLayout
* **Architecture:** MVVM / Unidirectional Data Flow (UDF) with Coroutines & Flow
* **Media & Networking:** ExoPlayer (Media3), Retrofit 2, OkHttp 3, Gson
* **Resolution Pipeline:** Headless Chromium WebViews with custom JavaScript sniffer hooks for automated stream harvesting

---

## ⚙️ Getting Started & Setup

1. **Clone the repository:**
   ```bash
   git clone https://github.com/cristianoashok/Stream-Right.git
   ```

2. **Configure Credentials:**
   Create a `local.properties` file in the root directory of the project and add your TMDB API key:
   ```properties
   TMDB_API_KEY=your_actual_tmdb_api_key_here
   ```

3. **Run the App:**
   Open the project in Android Studio, allow Gradle to sync, and run it on your emulator or physical device.

---

## 📄 License
This project is built for portfolio and educational demonstration purposes.
