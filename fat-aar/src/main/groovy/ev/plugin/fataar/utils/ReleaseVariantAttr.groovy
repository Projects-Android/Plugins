package ev.plugin.fataar.utils

import com.android.build.api.attributes.VariantAttr

class ReleaseVariantAttr implements VariantAttr, Serializable {

    private final String name

    ReleaseVariantAttr() {
        this.name = "release"
    }

    @Override
    String getName() {
        return name
    }
}
