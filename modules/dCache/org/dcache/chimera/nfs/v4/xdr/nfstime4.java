/*
 * Automatically generated by jrpcgen 1.0.7 on 2/21/09 1:22 AM
 * jrpcgen is part of the "Remote Tea" ONC/RPC package for Java
 * See http://remotetea.sourceforge.net for details
 */
package org.dcache.chimera.nfs.v4.xdr;
import org.dcache.xdr.*;
import java.io.IOException;

public class nfstime4 implements XdrAble {
    public int64_t seconds;
    public uint32_t nseconds;

    public nfstime4() {
    }

    public nfstime4(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        xdrDecode(xdr);
    }

    public void xdrEncode(XdrEncodingStream xdr)
           throws OncRpcException, IOException {
        seconds.xdrEncode(xdr);
        nseconds.xdrEncode(xdr);
    }

    public void xdrDecode(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        seconds = new int64_t(xdr);
        nseconds = new uint32_t(xdr);
    }

}
// End of nfstime4.java
