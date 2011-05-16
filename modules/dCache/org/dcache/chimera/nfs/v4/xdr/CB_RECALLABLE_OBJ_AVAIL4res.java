/*
 * Automatically generated by jrpcgen 1.0.7 on 2/21/09 1:22 AM
 * jrpcgen is part of the "Remote Tea" ONC/RPC package for Java
 * See http://remotetea.sourceforge.net for details
 */
package org.dcache.chimera.nfs.v4.xdr;
import org.dcache.xdr.*;
import java.io.IOException;

public class CB_RECALLABLE_OBJ_AVAIL4res implements XdrAble {
    public int croa_status;

    public CB_RECALLABLE_OBJ_AVAIL4res() {
    }

    public CB_RECALLABLE_OBJ_AVAIL4res(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        xdrDecode(xdr);
    }

    public void xdrEncode(XdrEncodingStream xdr)
           throws OncRpcException, IOException {
        xdr.xdrEncodeInt(croa_status);
    }

    public void xdrDecode(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        croa_status = xdr.xdrDecodeInt();
    }

}
// End of CB_RECALLABLE_OBJ_AVAIL4res.java
