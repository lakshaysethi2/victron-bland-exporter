.PHONY: build clean

build:
	@echo "Building APK using Docker Compose..."
	docker compose up builder
	@echo ""
	@echo "Build process finished. If successful, your APK is at:"
	@echo "  app/build/outputs/apk/debug/app-debug.apk"

clean:
	@echo "Cleaning up using Docker Compose..."
	docker compose run --rm builder bash -c "if [ -f gradlew ]; then ./gradlew clean; else gradle clean; fi"
