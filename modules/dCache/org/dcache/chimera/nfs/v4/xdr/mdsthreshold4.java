/*
 * Automatically generated by jrpcgen 1.0.7 on 2/21/09 1:22 AM
 * jrpcgen is part of the "Remote Tea" ONC/RPC package for Java
 * See http://remotetea.sourceforge.net for details
 */
package org.dcache.chimera.nfs.v4.xdr;
import org.dcache.xdr.*;
import java.io.IOException;

public class mdsthreshold4 implements XdrAble {
    public threshold_item4 [] mth_hints;

    public mdsthreshold4() {
    }

    public mdsthreshold4(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        xdrDecode(xdr);
    }

    public void xdrEncode(XdrEncodingStream xdr)
           throws OncRpcException, IOException {
        { int $size = mth_hints.length; xdr.xdrEncodeInt($size); for ( int $idx = 0; $idx < $size; ++$idx ) { mth_hints[$idx].xdrEncode(xdr); } }
    }

    public void xdrDecode(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        { int $size = xdr.xdrDecodeInt(); mth_hints = new threshold_item4[$size]; for ( int $idx = 0; $idx < $size; ++$idx ) { mth_hints[$idx] = new threshold_item4(xdr); } }
    }

}
// End of mdsthreshold4.java
