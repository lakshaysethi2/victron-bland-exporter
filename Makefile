.PHONY: build clean test test-linux test-android help

# Two independent halves live in this repo (see AGENTS.md -> Repository layout):
#   app/    Android exporter  - Kotlin, Gradle, needs a JDK + Android SDK
#   linux/  Linux host service - Python 3.11, venv + pytest, needs neither
# Keep them separate: a target here either builds/tests one half or the other.

PYTHON ?= python3

help:
	@echo "Android half (app/):"
	@echo "  make build        build a debug APK in the Android build-box container"
	@echo "  make test-android run the app's JVM unit tests (needs JDK + Android SDK)"
	@echo "  make clean        clean the Gradle build in the container"
	@echo ""
	@echo "Linux half (linux/):"
	@echo "  make test-linux   run the host service's pytest suite"
	@echo ""
	@echo "Both:"
	@echo "  make test         test-linux + test-android"

build:
	@echo "Building APK using Docker Compose..."
	docker compose run --rm builder
	@echo ""
	@echo "Build process finished. If successful, your APK is at:"
	@echo "  app/build/outputs/apk/debug/app-debug.apk"

clean:
	@echo "Cleaning up using Docker Compose..."
	docker compose run --rm builder bash -c "if [ -f gradlew ]; then ./gradlew clean; fi"

# --- Linux half -----------------------------------------------------------
# Host Python only. Install deps once with:
#   python3 -m venv ~/.venv/mppt-ble && ~/.venv/mppt-ble/bin/pip install -r linux/requirements.txt
# then run: make test-linux PYTHON=~/.venv/mppt-ble/bin/python
test-linux:
	@echo "Testing the Linux host service (linux/)..."
	cd linux && $(PYTHON) -m pytest -q

# --- Android half ---------------------------------------------------------
# JVM unit tests (Robolectric). Needs a JDK 17 and an Android SDK with
# sdk.dir set in the gitignored local.properties; use `make build` for the
# containerised APK build when the host has no SDK.
test-android:
	@echo "Testing the Android app (app/)..."
	./gradlew :app:testDebugUnitTest

test: test-linux test-android
	@echo ""
	@echo "Both halves green."
