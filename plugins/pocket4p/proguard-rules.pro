# Module entry points are instantiated from custom manifest metadata.
-keep class * implements dev.pillar.osmodule.modules.AppModule { public <init>(); }
