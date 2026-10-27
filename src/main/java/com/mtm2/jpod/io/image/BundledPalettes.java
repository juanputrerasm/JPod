package com.mtm2.jpod.io.image;

import com.mtm2.jpod.io.RawImageDecoder;

import java.util.Base64;

/** Game-specific fallback palettes shared by RAW and model previews. */
public final class BundledPalettes {

    private static volatile int[] cpr;
    private static volatile int[] hellbender;
    private static volatile int[] terminalVelocity;

    private BundledPalettes() {}

    /** The MTM1 METALCR2 palette, the one bundled as a classpath resource. */
    public static int[] metalCr2Mtm1() {
        return RawImageDecoder.loadResourcePalette();
    }

    /** CPR's METALCR2, which differs from the MTM1 and MTM2 copy. */
    public static int[] metalCr2Cpr() {
        if (cpr == null) cpr = decode(CPR_BASE64);
        return cpr.clone();
    }

    public static int[] vgaHellbender() {
        if (hellbender == null) hellbender = decode(HELLBENDER_BASE64);
        return hellbender.clone();
    }

    /** VGA.ACT as shipped by Terminal Velocity and Fury3. */
    public static int[] vgaTerminalVelocity() {
        if (terminalVelocity == null) terminalVelocity = decode(TERMINAL_VELOCITY_BASE64);
        return terminalVelocity.clone();
    }

    private static int[] decode(String encoded) {
        return RawImageDecoder.decodeAct(Base64.getDecoder().decode(encoded));
    }

    private static final String CPR_BASE64 =
            "AAAAgAAAAIAAgIAAAACAgACAAICAwMDAwNzApsrwAAAADgAABQUFCAgICwoKABQUCAg/KQUBEBAQCgpPAAC/DAxe"
            + "Bh4UGBgXGRgYDg5uGRkZQggFMwg/EBB9HRwaDCgUTwoKEhKOISEhIyEeQwpPFBSeUREOXgwKEjIUKiUlFhavKCgj"
            + "CD8IKSkoUgxeGBi/bg4OLS0mGDwUYRkQGhrPNS0sfBAPMTExHBzfMjMqYg5uCk8KOzIwHkYVHh7vcCERNzkujhIS"
            + "ICD/Pz8IQTY0cRB9vwAAOjo6Oj4xlRgGI1AVDF4MnhQURzs4ghKOQkJCTT46Pkc2gS0WKVoVrxcSDm4OU0M+T08K"
            + "GFpzkhSeQVA7SkpKvxgYL2QVWkhBkTUYvwC/Q1g/zxoaEH0QoxavyyEJUlJSYU1GNW4VXl4MSWBFoDwZ3xwcsxi/"
            + "Wlpaa1VMEo4SIXOEUGpLsUIb9R8fwRrPTU3/dFtRY2Njbm4O0j0MFJ4UVXJRv0wef2NWa2trUIYnWntWKYyMFq8W"
            + "AL8AfX0Qimtbc3NzYINc11YTlHJfGL8Ye3t7MZycZo1ijo4Sn3lk/01Nap45hISEOaWlGs8a9GIVAL+/bplqeXn/"
            + "33QTp4NsjIyMQq2t70j/np4UHN8crYt0lJSUfaZ6/3cjHu8eSr21tJV9nJyc5ZAWhbdLr68Wi7KJ/3p6Usa9u52G"
            + "I/8jpaWlv78AWs7GwaWOv78Yra2tmLyX+Hn+/5hP7KwZpqb/Y9bKyK6Xn89cocKhtbW1z88aqcipzrihc97Ovb29"
            + "rcytwMDA/6ampMjw8sgd1L+pxcXF/7l6ev96398c/6b+2sezgu3duuduzs7OwNzAhPfn+eMg1tbW7+8e3t7e/9qm"
            + "nP/31P+A5ubm//8g//9N7+/v//95//+m9/f3////AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            + "AAAAAAAAAAAAAAAA//vwoKCkgICA/wAAAP8A//8AAAD//wD/AP//////";

    private static final String HELLBENDER_BASE64 =
            "AAAACAgIEBAQGRkZISEhKSkpMTExOjo6QkJCSkpKUlJSWlpaY2Nja2trc3Nze3t7hISEjIyMlJSUnJycpaWlra2t"
            + "tbW1vb29xcXFzs7O1tbW3t7e5ubm7+/v9/f3////AxADBRgFByAHCSgJCzAMDjcQED8TFEUWFksaGlEeHlciIl0o"
            + "J2IsK2gxMG03NnM/QXtJS4NTVoxdYJRna5xxcaB3eaZ/gayGibKOkbiVmr6dosOkqsmsss+zutW7wtvCBgYGCwoK"
            + "EA8PFBMTGRgYHxwcJSEgKiUlLykoNS0sOzIwQTY0Rzs4TT46U0M+WkhBYU1Ga1VMdFtRf2NWimtblHJfn3lkp4Ns"
            + "rYt0tJV9u52GwaWOyK6Xzrih1L+p2sezCAhABwdbBgZ3BQWSAwOtAgLIAQHkAAD/HR3/Ojr/V1f/dHT/kZH/rq7/"
            + "y8v/6Oj/CEAIB1sHBncGBZIFA60DAsgCAeQBAP8AHf8dOv86V/9XdP90kf+Rrv+uy//L6P/oDgAAKQUBRAkDXw4E"
            + "ehMFlRgGsBwIyyEJ0j0M2FkQ33QT5ZAW7KwZ8sgd+eMg//8jWRAGbBMHfhYIkRkIoxwJth4KyCEL2yQL7ScM8EAp"
            + "8llG9XJj94yA+qWd/L66/9fXNw4EURgGbCQHiDMJokELvFQN1mkP8YEQ9Jkr9a5G98Fg+NF7+t2V/Oqx/vTL//3o"
            + "NQwIQxcLUSIOXy4SbTkVe0QYiFEelV0joWoprnYuu4M0x5A7055C36tK67lR98ZYIgUiJgU1KwRILwRbNANuOAOB"
            + "PQKUQQKnWRCycR69iCzHoDrSuEfd0FXo52Py/3H9ABQUBh4UDCgUEjIUGDwUHkYVI1AVKVoVL2QVNW4VUIYnap45"
            + "hbdLn89cuudu1P+AGFpzIXOEKYyMMZycOaWlQq2tSr21Usa9Ws7GY9bGY9bOc97Oe+fehO/ehPfnnP/3AAAAAAAA"
            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private static final String TERMINAL_VELOCITY_BASE64 =
            "AAAABwcHDg4OFhYWHR0dJCQkKysrMzMzOjo6QUFBSEhIT09PV1dXXl5eZWVlbGxsdHR0e3t7goKCiYmJkZGRmJiY"
            + "n5+fpqamra2ttbW1vLy8w8PDysrK0tLS2dnZ4ODgBQUFCgkJDg0NExISGBgXHRwaIyEeKCgjLS0mMjMqNzkuOj4x"
            + "PUQ1QEs4QVA7Q1g/SWBFUGpLVXJRWntWYINcZo1ibJZncZ1ueaN2gqp/ibCHkbaPmLyXocKhqciprcytBgYGCwoK"
            + "EA8PFBMTGRgYHxwcJSEgKiUlLykoNS0sOzIwQTY0Rzs4TT46U0M+WkhBYU1Ga1VMdFtRf2NWimtblHJfn3lkp4Ns"
            + "rYt0tJV9u52GwaWOyK6Xzrih1L+p2sezAwMQBQUcCAgnCgozDg08EhFGFxRRHBhbIh1kJyJtLid2NCx/PDKFQjiN"
            + "Sz6VU0mcV02kW1CrYVewa1+0cme2em+6gXe8iYC/kIjDl4/Gn5jJp6DNrqfQta/Uu7bXw77bDgAAGwAAKAABNQAB"
            + "QgABTwABXAACaQACdgACgwACkAADnQADqgADtwADxAAExw8HyyEJ0DQL1EYN2FkQ3WsS4X4U5ZAW6aMY7rUa8sgd"
            + "9tof++0h//8j//9s//+2////CAggEBBAGBhgICCAKCigMDDAODjgPz//CCAgEEBAGGBgIICAKKCgMMDAOODgP///"
            + "OA8FRhYHVR8KYikNbzQQfUEUiU4YlVscomghrXYmt4Usw5Yyy6U6zLBJzbpaz8JpAgIlCgUsEgkzGgw6IxBBKxNI"
            + "MxdPOxpWQx1dSyFkVCRrXChyZCt5bC6AdDKHfDWOhTmUjTyblUCinUOppUawrUq3tk2+vlHFxlTMzlfT1lva3l7h"
            + "52Lo72Xv92n2/2z9Nw4EURgGbCQHiDMJokELvFQN1mkP8YEQ9Jkr9a5G98Fg+NF7+t2V/Oqx/vTL//3oAAAAAAAA"
            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
}
