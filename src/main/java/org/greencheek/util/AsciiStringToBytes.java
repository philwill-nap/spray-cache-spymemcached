package org.greencheek.util;

/**
 * Created by dominictootell on 08/06/2014.
 */
public class AsciiStringToBytes {
    /**
     * See http://java-performance.info/charset-encoding-decoding-java-78/#more-744
     *
     * @param str the string to obtain the ascii byte array for
     * @return the byte array for the ascii string
     */
    public static byte[] getBytes( final String str )
    {
        final byte[] res = new byte[ str.length() ];
        for (int i = 0; i < str.length(); i++)
            res[ i ] = (byte) str.charAt( i );
        return res;
    }
}
