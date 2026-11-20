# kotlinx.serialization keeps its generated serializers through the plugin;
# the models of :core are kept by its consumer rules.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
