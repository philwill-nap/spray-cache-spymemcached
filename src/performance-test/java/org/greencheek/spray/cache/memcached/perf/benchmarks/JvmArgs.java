package org.greencheek.spray.cache.memcached.perf.benchmarks;

/**
 * Created by dominictootell on 07/06/2014.
 */
public class JvmArgs {
    private final static String[] JFR_JVM_ARGS = new String[]{"-server","-XX:+UnlockCommercialFeatures","-XX:+FlightRecorder","-XX:FlightRecorderOptions=defaultrecording=true,settings=profile,filename=target/recording.jfr"};
    private final static String[] JVM_ARGS = new String[]{"-server"};

    public static String[] getJvmArgs() {
        String profile = System.getProperty("enablejfr","true");
        boolean flightRecorderEnabled;
        if(profile==null || profile.trim().length()==0) {
            flightRecorderEnabled = true;
        } else {
            if(profile.equalsIgnoreCase("true")) {
                flightRecorderEnabled = true;
            } else {
                flightRecorderEnabled = false;
            }
        }
        return flightRecorderEnabled ? JFR_JVM_ARGS : JVM_ARGS;
    }

}
