# AppModule implementations are named by custom manifest metadata, which R8 cannot infer as a
# reflection root. Feature consumer rules keep the current entries; this generic rule protects new
# modules until their own consumer rule is added.
-keep class * implements dev.konraditurbe.osmosis.modules.AppModule { public <init>(); }
