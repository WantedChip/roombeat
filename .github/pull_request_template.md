## Description
<!-- Provide a clear and concise summary of what changes were made and why. -->

## Linked Issues
<!-- e.g. Fixes #123, Closes #456 -->
Fixes #

## Type of Change
- [ ] 🐛 Bug fix (non-breaking change which fixes an issue)
- [ ] ✨ New feature (non-breaking change which adds functionality)
- [ ] ⚡ Performance improvement (sub-10ms audio, buffer optimization, memory)
- [ ] ♻️ Code refactoring (no functional changes)
- [ ] 📝 Documentation update (README, privacy policy, architectural docs)
- [ ] 🧪 Tests (unit tests, instrumentation stress tests)
- [ ] 🔧 Build / CI / Toolchain (Gradle, ProGuard, Astro, GitHub Actions)

## Area of Impact
- [ ] Native C++ / Oboe / Opus / Resampler (`android/app/src/main/cpp`)
- [ ] Android App / Compose / Services (`android/app/src/main/java`)
- [ ] Android Tests & Instrumentation (`android/app/src/androidTest`, `test`)
- [ ] Landing Page / WebAudio Simulator (`site/`)
- [ ] CI Workflows & Community Docs (`.github/`, root docs)

## Verification Checklist
- [ ] My code follows the code style and guidelines of this repository.
- [ ] I have verified that all unit tests pass cleanly (`./gradlew test` in `android/` and `npm test` in `site/`).
- [ ] I have verified that Android lint passes (`./gradlew lintDebug`).
- [ ] I have not introduced any temporary bypasses, suppressed lints, or TODO band-aids.
- [ ] I have updated relevant documentation if applicable.
- [ ] My commit messages follow [Conventional Commits](https://www.conventionalcommits.org/).
