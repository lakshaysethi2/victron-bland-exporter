.PHONY: build clean

build:
	@echo "Starting build process..."
	@if [ -x "./gradlew" ]; then \
		./gradlew assembleDebug; \
	elif command -v gradle >/dev/null 2>&1; then \
		gradle assembleDebug; \
	else \
		echo "Error: Neither ./gradlew nor gradle command found. Please open the project in Android Studio or install Gradle."; \
		exit 1; \
	fi
	@echo "Build successful! APK should be located at: app/build/outputs/apk/debug/app-debug.apk"

clean:
	@if [ -x "./gradlew" ]; then \
		./gradlew clean; \
	elif command -v gradle >/dev/null 2>&1; then \
		gradle clean; \
	else \
		echo "Error: Gradle not found."; \
		exit 1; \
	fi
