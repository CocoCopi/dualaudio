# ---------------------------------------------------------------------------
# Shizuku user service
# ---------------------------------------------------------------------------
# Shizuku starts this class by name in a separate app_process, then calls its
# no-argument constructor. Neither the name nor the constructor may be renamed.
-keep class com.cococopi.dualaudio.routing.AudioRoutingUserService {
    <init>();
    <init>(android.content.Context);
}
-keepnames class com.cococopi.dualaudio.routing.AudioRoutingUserService

# The AIDL Stub and its transaction methods are called from the app process.
-keep class com.cococopi.dualaudio.routing.IAudioRoutingService { *; }
-keep class com.cococopi.dualaudio.routing.IAudioRoutingService$Stub { *; }
-keep class com.cococopi.dualaudio.routing.IAudioRoutingService$Stub$Proxy { *; }

# Reflection inside the shell process against framework classes. These classes
# are provided by the platform, so only their names used by reflection matter.
-keepclassmembers class android.media.AudioDeviceInfo {
    public *;
}
-keepclassmembers class android.media.AudioManager {
    public *;
}
-keepclassmembers class android.media.AudioDeviceAttributes {
    public *;
}

# Keep annotations used by Shizuku's permission APIs.
-keepattributes *Annotation*
