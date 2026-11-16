# CastContext instantiates the OptionsProvider by its manifest metadata class
# name. R8 cannot see that reflective edge when shrinking the release APK.
-keep class com.reianix.reianix_local.player.cast.ReiAnixCastOptionsProvider {
    public <init>();
    *;
}
