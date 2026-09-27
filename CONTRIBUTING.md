# Contributing to RoomBeat

Thank you for your interest in contributing to RoomBeat! We welcome contributions to our native Android audio engine, user interface, local networking protocol, and landing page.

Please review this document before submitting contributions.

---

## Code of Conduct

All contributors are expected to uphold the [Contributor Covenant Code of Conduct](./CODE_OF_CONDUCT.md). Please report any unacceptable behavior to **ty121rt@gmail.com**.

---

## Repository Structure

RoomBeat is structured as a monorepo:

```
RoomBeat/
├── android/                 # Native Android application
│   ├── app/
│   │   ├── src/main/cpp/    # C++20 audio hot path (Oboe, Opus, Sinc Resampler)
│   │   ├── src/main/java/   # Kotlin & Jetpack Compose implementation
│   │   ├── src/main/assets/ # In-app offline privacy policy and licenses
│   │   └── src/test/        # Unit & release verification tests
│   └── build.gradle.kts     # Android build configuration
├── site/                    # Landing page & WebAudio interactive simulator
│   ├── src/                 # Astro components & Vanilla JS WebAudio engine
│   ├── public/              # Static edge assets, headers, and robots.txt
│   └── package.json         # Tailwind CSS v4 & Astro dependencies
├── .github/                 # Issue templates, PR template, and CI workflows
├── CHANGELOG.md             # Project release history
├── CODE_OF_CONDUCT.md       # Community standards
├── CONTRIBUTING.md          # This contributor guide
└── SECURITY.md              # Vulnerability reporting protocol
```

---

## Prerequisites & Development Setup

### 1. Android Development (`android/`)
- **JDK**: Java Development Kit 17 (Eclipse Temurin 17 recommended)
- **Android SDK**: Compile / Target SDK 35, Min SDK 30
- **Android NDK**: NDK r27c / CMake 3.22+ (configured via `externalNativeBuild`)
- **Android Studio**: Android Studio Ladybug (2024.2+) or newer

#### Build & Test Commands:
```bash
# In the android/ directory:
./gradlew lintDebug           # Run Android Lint static analysis
./gradlew test                # Run all JVM unit tests (debug & release)
./gradlew assembleDebug       # Build developer debug APK
./gradlew assembleRelease     # Build production release APKs with R8 shrinking
```

### 2. Landing Page & WebAudio Simulator (`site/`)
- **Node.js**: v22 LTS or newer
- **Package Manager**: npm

#### Build & Test Commands:
```bash
# In the site/ directory:
npm ci                        # Install dependencies cleanly
npm run dev                   # Start Astro local development server (http://localhost:4321)
npm run build                 # Compile Tailwind CSS v4 and static Astro pages
npm test                      # Run site build & asset validation tests
```

---

## Git Workflow & Conventional Commits

1. **Fork and Branch**:
   - Create a feature or bugfix branch off `main`:
     ```bash
     git checkout -b feat/dynamic-resampler-filter
     # or
     git checkout -b fix/multicast-lock-leak
     ```
2. **Commit Messages**:
   - Follow the [Conventional Commits](https://www.conventionalcommits.org/) specification:
     - `feat:` for new capabilities
     - `fix:` for bug fixes
     - `perf:` for performance optimizations
     - `test:` for test additions or improvements
     - `docs:` for documentation updates
     - `chore:` for build, dependency, or tooling updates
     - `ci:` for CI/CD workflow changes
3. **No Band-Aids**:
   - All tests and lints must pass cleanly at root cause.
   - Do not use temporary suppressions, disabled compiler flags, or `# TODO fix later` workarounds.
4. **Pull Requests**:
   - Submit your pull request against the `main` branch.
   - Fill out all sections in the [Pull Request Template](./.github/pull_request_template.md).
   - Ensure the automated Android CI and Site CI checks pass.

---

## Architecture & Code Guidelines

### Native C++ Audio Path (`android/app/src/main/cpp/`)
- Audio hot path must run with deterministic real-time safety: **no heap memory allocations (`malloc`/`new`), blocking mutexes, or I/O** inside Oboe audio rendering callbacks.
- All timestamps must reference the monotonic clock (`CLOCK_MONOTONIC_RAW`).

### Jetpack Compose UI (`android/app/src/main/java/com/roombeat/app/ui/`)
- Adhere strictly to the **Tactile Acoustic Industrial** aesthetic:
  - Backgrounds: `#0B0C0E` (Chassis Base), `#13151A` (Module Surface).
  - Signal accents: `#FF5500` (Action Triggers), `#00E599` (Phosphor Lock), `#FFB800` (Amber Warning), `#FF334B` (Error Drop).
  - Strictly **no generic purple gradients, glassmorphism, or pill-shaped buttons**.

Thank you for building high-performance, open-source distributed audio with us!
