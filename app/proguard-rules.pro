# Add project specific ProGuard rules here.
# Keep Victron parser classes
-keep class com.lakshaysethi.victronbleexporter.parser.** { *; }
-keep class com.lakshaysethi.victronbleexporter.exporter.** { *; }
-keep class com.lakshaysethi.victronbleexporter.tunnel.** { *; }
# For cloudflared process
-keep class com.lakshaysethi.victronbleexporter.service.** { *; }