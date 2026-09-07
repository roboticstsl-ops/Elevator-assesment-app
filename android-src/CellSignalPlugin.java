package com.tsl.rfsurvey;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Reads serving-cell RSRP / RSRQ / SINR / band from the modem. Android only.
 *
 * Primary path: TelephonyManager.getSignalStrength() (API 28/29+) — reliable,
 * needs only READ_PHONE_STATE, no location toggle.
 * getAllCellInfo() is used only as a best-effort source of the BAND, and never
 * fails the call when it is empty (it commonly is — depends on device state).
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
        boolean phone = getPermissionState("phone") == PermissionState.GRANTED;
        boolean loc = getPermissionState("location") == PermissionState.GRANTED;
        if (!phone || !loc) {
            requestAllPermissions(call, "afterPerms");
            return;
        }
        doRead(call);
    }

    @PermissionCallback
    private void afterPerms(PluginCall call) {
        if (getPermissionState("phone") == PermissionState.GRANTED) {
            doRead(call);                     // location is optional (band only)
        } else {
            call.reject("Phone permission denied — enable it in App info › Permissions");
        }
    }

    @SuppressLint("MissingPermission")
    private void doRead(PluginCall call) {
        try {
            TelephonyManager base =
                (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
            if (base == null) { call.reject("No telephony service on this device"); return; }

            TelephonyManager tm = forCarrier(base, call.getString("carrier"));
            JSObject r = new JSObject();

            List<CellInfo> cells = freshCellInfo(tm);

            boolean gotSignal = fromSignalStrength(tm, r);
            if (!gotSignal) gotSignal = fromCellInfo(cells, r);   // fallback for signal
            try { addBand(cells, r); } catch (Throwable ignore) {} // best-effort

            boolean locOk = getPermissionState("location") == PermissionState.GRANTED;
            if (!r.has("band")) r.put("bandHint", locOk ? "no-cell-identity" : "need-location");

            if (!r.has("rsrp") && !r.has("rsrq")) {
                call.reject("No signal data — check the SIM is active, then retry");
                return;
            }
            call.resolve(r);
        } catch (Exception e) {
            call.reject("read failed: " + e.getMessage());
        }
    }

    /* ---- primary: SignalStrength (no location needed) ---- */
    @SuppressLint("MissingPermission")
    private boolean fromSignalStrength(TelephonyManager tm, JSObject r) {
        if (Build.VERSION.SDK_INT < 29) return false;
        SignalStrength ss = tm.getSignalStrength();
        if (ss == null) return false;
        for (CellSignalStrength c : ss.getCellSignalStrengths()) {
            if (c instanceof CellSignalStrengthLte) {
                CellSignalStrengthLte l = (CellSignalStrengthLte) c;
                putIf(r, "rsrp", l.getRsrp());
                putIf(r, "rsrq", l.getRsrq());
                int snr = l.getRssnr();
                if (snr != UNAVAIL) {
                    if (Math.abs(snr) > 40) snr = Math.round(snr / 10f);
                    r.put("sinr", snr);
                }
                r.put("tech", "LTE");
                return r.has("rsrp");
            }
            if (c instanceof CellSignalStrengthNr) {
                CellSignalStrengthNr n = (CellSignalStrengthNr) c;
                putIf(r, "rsrp", n.getSsRsrp());
                putIf(r, "rsrq", n.getSsRsrq());
                int snr = n.getSsSinr();
                if (snr != UNAVAIL) r.put("sinr", snr);
                r.put("tech", "5G");
                return r.has("rsrp");
            }
        }
        return false;
    }

    /* ---- force a fresh cell-info read (API 29+), fall back to cached ---- */
    @SuppressLint("MissingPermission")
    private List<CellInfo> freshCellInfo(TelephonyManager tm) {
        if (Build.VERSION.SDK_INT >= 29) {
            final CountDownLatch latch = new CountDownLatch(1);
            @SuppressWarnings("unchecked")
            final List<CellInfo>[] box = new List[]{ null };
            try {
                tm.requestCellInfoUpdate(Executors.newSingleThreadExecutor(),
                    new TelephonyManager.CellInfoCallback() {
                        @Override public void onCellInfo(List<CellInfo> ci) { box[0] = ci; latch.countDown(); }
                        @Override public void onError(int code, Throwable e) { latch.countDown(); }
                    });
                latch.await(3, TimeUnit.SECONDS);
            } catch (Throwable ignore) {}
            if (box[0] != null && !box[0].isEmpty()) return box[0];
        }
        try { return tm.getAllCellInfo(); } catch (SecurityException se) { return null; }
    }

    /* ---- fallback: getAllCellInfo signal ---- */
    private boolean fromCellInfo(List<CellInfo> cells, JSObject r) {
        if (cells == null) return false;
        for (CellInfo ci : cells) {
            if (!ci.isRegistered()) continue;
            if (ci instanceof CellInfoLte) {
                CellSignalStrengthLte s = ((CellInfoLte) ci).getCellSignalStrength();
                putIf(r, "rsrp", s.getRsrp());
                putIf(r, "rsrq", s.getRsrq());
                if (Build.VERSION.SDK_INT >= 26) {
                    int snr = s.getRssnr();
                    if (snr != UNAVAIL) {
                        if (Math.abs(snr) > 40) snr = Math.round(snr / 10f);
                        r.put("sinr", snr);
                    }
                }
                r.put("tech", "LTE");
                return r.has("rsrp");
            }
            if (Build.VERSION.SDK_INT >= 29 && ci instanceof CellInfoNr) {
                CellSignalStrengthNr s = (CellSignalStrengthNr) ((CellInfoNr) ci).getCellSignalStrength();
                putIf(r, "rsrp", s.getSsRsrp());
                putIf(r, "rsrq", s.getSsRsrq());
                int snr = s.getSsSinr();
                if (snr != UNAVAIL) r.put("sinr", snr);
                r.put("tech", "5G");
                return r.has("rsrp");
            }
        }
        return false;
    }

    /* ---- best-effort band from cell identity (needs location on Android 10+) ---- */
    private void addBand(List<CellInfo> cells, JSObject r) {
        if (cells == null) return;
        for (CellInfo ci : cells) {
            if (!ci.isRegistered()) continue;
            if (ci instanceof CellInfoLte) {
                String b = lteBand(((CellInfoLte) ci).getCellIdentity());
                if (!b.isEmpty()) r.put("band", b);
                return;
            }
            if (Build.VERSION.SDK_INT >= 30 && ci instanceof CellInfoNr) {
                Object id = ((CellInfoNr) ci).getCellIdentity();
                if (id instanceof CellIdentityNr) {
                    int[] bands = ((CellIdentityNr) id).getBands();
                    if (bands != null && bands.length > 0) r.put("band", "n" + bands[0]);
                }
                return;
            }
        }
    }

    /* ---- dual-SIM: pick the subscription whose carrier matches ---- */
    @SuppressLint("MissingPermission")
    private TelephonyManager forCarrier(TelephonyManager base, String carrier) {
        if (carrier == null || Build.VERSION.SDK_INT < 24) return base;
        try {
            SubscriptionManager sm =
                (SubscriptionManager) getContext().getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
            if (subs == null) return base;
            boolean wantDu = carrier.equalsIgnoreCase("du");
            for (SubscriptionInfo si : subs) {
                String name = String.valueOf(si.getCarrierName()).toLowerCase();
                boolean isDu = name.contains("du");
                boolean isEt = name.contains("e&") || name.contains("etisalat") || name.contains("eand");
                if ((wantDu && isDu) || (!wantDu && isEt)) {
                    return base.createForSubscriptionId(si.getSubscriptionId());
                }
            }
        } catch (Throwable ignore) {}
        return base;
    }

    private void putIf(JSObject o, String k, int v) {
        if (v != UNAVAIL && v != 0) o.put(k, v);
    }

    private String lteBand(CellIdentityLte id) {
        if (id == null) return "";
        if (Build.VERSION.SDK_INT >= 30) {
            int[] b = id.getBands();
            if (b != null && b.length > 0) return "B" + b[0];
        }
        return lteBandFromEarfcn(id.getEarfcn());
    }

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
