package com.tsl.rfsurvey;

import android.Manifest;
import android.content.Context;
import android.os.Build;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.TelephonyManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.util.List;

/**
 * Reads serving-cell RSRP / RSRQ / SINR / band from the modem.
 * Android only. Needs READ_PHONE_STATE + fine location, and Location services ON
 * (getAllCellInfo returns nothing otherwise on Android 10+).
 */
@CapacitorPlugin(
    name = "CellSignal",
    permissions = {
        @Permission(alias = "phone", strings = { Manifest.permission.READ_PHONE_STATE }),
        @Permission(alias = "location", strings = { Manifest.permission.ACCESS_FINE_LOCATION })
    }
)
public class CellSignalPlugin extends Plugin {

    private static final int UNAVAIL = Integer.MAX_VALUE;

    @PluginMethod
    public void read(PluginCall call) {
        if (getPermissionState("phone") != PermissionState.GRANTED
                || getPermissionState("location") != PermissionState.GRANTED) {
            requestAllPermissions(call, "afterPerms");
            return;
        }
        doRead(call);
    }

    @PermissionCallback
    private void afterPerms(PluginCall call) {
        if (getPermissionState("phone") == PermissionState.GRANTED
                && getPermissionState("location") == PermissionState.GRANTED) {
            doRead(call);
        } else {
            call.reject("Phone or Location permission denied");
        }
    }

    private void doRead(PluginCall call) {
        try {
            TelephonyManager tm =
                (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) { call.reject("No telephony service"); return; }

            List<CellInfo> cells;
            try {
                cells = tm.getAllCellInfo();
            } catch (SecurityException se) {
                call.reject("Missing permission for cell info"); return;
            }
            if (cells == null || cells.isEmpty()) {
                call.reject("No cell info — turn Location ON and retry"); return;
            }

            for (CellInfo ci : cells) {
                if (!ci.isRegistered()) continue;

                if (ci instanceof CellInfoLte) {
                    CellInfoLte lte = (CellInfoLte) ci;
                    CellSignalStrengthLte ss = lte.getCellSignalStrength();
                    JSObject r = new JSObject();
                    r.put("tech", "LTE");
                    putIf(r, "rsrp", ss.getRsrp());
                    putIf(r, "rsrq", ss.getRsrq());
                    int snr = (Build.VERSION.SDK_INT >= 26) ? ss.getRssnr() : UNAVAIL;
                    if (snr != UNAVAIL) {
                        if (Math.abs(snr) > 40) snr = Math.round(snr / 10f); // some OEMs report 0.1 dB
                        r.put("sinr", snr);
                    }
                    r.put("band", lteBand(lte.getCellIdentity()));
                    call.resolve(r);
                    return;
                }

                if (Build.VERSION.SDK_INT >= 29 && ci instanceof CellInfoNr) {
                    CellInfoNr nr = (CellInfoNr) ci;
                    CellSignalStrengthNr ss = (CellSignalStrengthNr) nr.getCellSignalStrength();
                    JSObject r = new JSObject();
                    r.put("tech", "5G");
                    putIf(r, "rsrp", ss.getSsRsrp());
                    putIf(r, "rsrq", ss.getSsRsrq());
                    int snr = ss.getSsSinr();
                    if (snr != UNAVAIL) r.put("sinr", snr);
                    r.put("band", nrBand(nr.getCellIdentity()));
                    call.resolve(r);
                    return;
                }
            }
            call.reject("No registered LTE/5G cell");
        } catch (Exception e) {
            call.reject("read failed: " + e.getMessage());
        }
    }

    private void putIf(JSObject o, String k, int v) {
        if (v != UNAVAIL && v != 0x7FFFFFFF) o.put(k, v);
    }

    private String lteBand(CellIdentityLte id) {
        if (id == null) return "";
        if (Build.VERSION.SDK_INT >= 30) {
            int[] b = id.getBands();
            if (b != null && b.length > 0) return "B" + b[0];
        }
        return lteBandFromEarfcn(id.getEarfcn());
    }

    private String nrBand(Object identity) {
        if (Build.VERSION.SDK_INT >= 30 && identity instanceof CellIdentityNr) {
            int[] b = ((CellIdentityNr) identity).getBands();
            if (b != null && b.length > 0) return "n" + b[0];
        }
        return "";
    }

    /** Rough EARFCN → LTE band, for devices below API 30. */
    private String lteBandFromEarfcn(int e) {
        if (e <= 0 || e == UNAVAIL) return "";
        if (e <= 599) return "B1";
        if (e >= 1200 && e <= 1949) return "B3";
        if (e >= 2400 && e <= 2649) return "B5";
        if (e >= 2750 && e <= 3449) return "B7";
        if (e >= 3450 && e <= 3799) return "B8";
        if (e >= 6150 && e <= 6449) return "B20";
        if (e >= 9210 && e <= 9659) return "B28";
        if (e >= 37750 && e <= 38249) return "B38";
        if (e >= 38650 && e <= 39649) return "B40";
        if (e >= 39650 && e <= 41589) return "B41";
        return "";
    }
}
