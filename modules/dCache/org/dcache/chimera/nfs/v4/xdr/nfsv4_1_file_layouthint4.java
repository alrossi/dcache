/*
 * Automatically generated by jrpcgen 1.0.7 on 2/21/09 1:22 AM
 * jrpcgen is part of the "Remote Tea" ONC/RPC package for Java
 * See http://remotetea.sourceforge.net for details
 */
package org.dcache.chimera.nfs.v4.xdr;
import org.dcache.xdr.*;
import java.io.IOException;

public class nfsv4_1_file_layouthint4 implements XdrAble {
    public uint32_t nflh_care;
    public nfl_util4 nflh_util;
    public count4 nflh_stripe_count;

    public nfsv4_1_file_layouthint4() {
    }

    public nfsv4_1_file_layouthint4(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        xdrDecode(xdr);
    }

    public void xdrEncode(XdrEncodingStream xdr)
           throws OncRpcException, IOException {
        nflh_care.xdrEncode(xdr);
        nflh_util.xdrEncode(xdr);
        nflh_stripe_count.xdrEncode(xdr);
    }

    public void xdrDecode(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        nflh_care = new uint32_t(xdr);
        nflh_util = new nfl_util4(xdr);
        nflh_stripe_count = new count4(xdr);
    }

}
// End of nfsv4_1_file_layouthint4.java
