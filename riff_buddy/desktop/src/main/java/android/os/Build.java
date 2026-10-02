package android.os;

/**
 * The two fields alphaTab reads outside its Android view: SDK_INT, to choose a
 * regex replacement method every desktop JDK has, and the device name for its
 * environment printout. Nothing else in alphaTab's shared code touches Android.
 */
public final class Build {
    public static final String MANUFACTURER = "desktop";
    public static final String MODEL = "jvm";

    public static final class VERSION {
        public static final int SDK_INT = 34;
    }

    private Build() {}
}
