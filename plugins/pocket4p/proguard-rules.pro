# Module entry points are instantiated from custom manifest metadata.
-keep class * implements dev.konraditurbe.osmosis.modules.AppModule { public <init>(); }
