# FireSMS ProGuard rules
-keepclassmembers class * extends androidx.room.RoomDatabase {
    abstract <methods>;
}
-keep class com.firesms.app.data.local.entity.** { *; }
-keep class com.firesms.app.data.remote.FireflyApiKt { *; }
-keep class com.firesms.app.domain.model.** { *; }
