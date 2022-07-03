/**
 * Collect passive measurements from serving and neighboring cells using Android API calls.
 * Cell info collected using TelephonyManager. Signal metrics collected using PhoneStateListener
 * callback function.
 *
 * @author Demetrius Davis (VT)
 */

package com.vt.avameasurements;

import static com.vt.avameasurements.MainActivity.duration_secs;
import static com.vt.avameasurements.MainActivity.interval_secs;
import static com.vt.avameasurements.MainActivity.start_time;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.telephony.CellIdentityLte;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthLte;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.List;

public class PassiveMeasurementTest implements Runnable, MeasurementTest {
    private final String _TAG_ = "PassiveMeasurementTest";
    Context context;
    Activity activity;
    MyPhoneStateListener mPhoneStateListener;
    TelephonyManager mTelephonyManager;
    OutputStreamWriter osw = null;
    String measurementReport = null;
    long currTime, endTime;
    long[][] pData = null;
    int MAX_VAL = 2147483647;
    Context tmpContext;

    /**
     * Class constructor for the passive measurement tests.
     *
     * @param appActivity  the text of the tool tip
     * @param appContext  the application Context
     * @param telephonyManager the telephonyManager instance
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    public PassiveMeasurementTest(Activity appActivity, Context appContext, TelephonyManager telephonyManager) {
        activity = appActivity; //activity.set(appActivity);
        context = appContext; //context.set(appContext);
        mTelephonyManager = telephonyManager; //mTelephonyManager.set(telephonyManager);
        mPhoneStateListener = new MyPhoneStateListener();

        initCSVFile();
    }

    /**
     * Required method for implementations of the Runnable interface.
     */
    @Override
    public void run() {
        if (MainActivity._DEBUG_) System.out.println("[PassiveMeasurementTest.runTest] Breakpoint 1");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            int subscriptionID = SubscriptionManager.getDefaultSubscriptionId();
            System.out.println("[" + _TAG_ + "] Subscription ID: " + subscriptionID);
        }
        // Initialize TelephonyManager callback function
        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);

        if (MainActivity._DEBUG_) System.out.println("[" + _TAG_ + ".runTest] Breakpoint 2 -- inside run()");

        long processingTime;
        currTime = System.currentTimeMillis(); // units: milliseconds
        endTime = currTime + ((long)duration_secs * 1000); // convert duration time from secs to ms

        if (MainActivity._DEBUG_) System.out.println("Initializing passive measurement collection... [" + currTime + "]");
        while (currTime < endTime) {
            createMeasurementReport();
            if (measurementReport.length() > 0) writeToCSV(measurementReport);

            currTime = System.currentTimeMillis();
            processingTime = System.currentTimeMillis() - currTime;
            processWait(((long)interval_secs * 1000) - processingTime);
        }
        closeCSVFile();
        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
        mPhoneStateListener = null;
    }

    class MyPhoneStateListener extends PhoneStateListener {
        @RequiresApi(api = Build.VERSION_CODES.Q)
        @Override
        public void onSignalStrengthsChanged(android.telephony.SignalStrength signalStrength) {
            super.onSignalStrengthsChanged(signalStrength);

            if (MainActivity._DEBUG_) System.out.println("[" + _TAG_ + "]: Inside onSignalStrengthsChanged() callback function... [" + currTime + "]");

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

                int s_cqi = MAX_VAL, s_rsrp = MAX_VAL, s_rsrq = MAX_VAL, s_rssnr = MAX_VAL, s_rssi = MAX_VAL;
                List<CellSignalStrength> cellStrengthList = signalStrength.getCellSignalStrengths();
                if (cellStrengthList.size() > 0) {
                    if (cellStrengthList.get(0).getClass().toString().equals("class android.telephony.CellSignalStrengthLte")) {
                        s_rsrp = ((CellSignalStrengthLte) cellStrengthList.get(0)).getRsrp(); // RSRP
                        s_rsrq = ((CellSignalStrengthLte) cellStrengthList.get(0)).getRsrq(); // RSRQ
                        s_rssi = ((CellSignalStrengthLte) cellStrengthList.get(0)).getRssi(); // RSSI
                        s_rssnr = ((CellSignalStrengthLte) cellStrengthList.get(0)).getRssnr(); // RSSNR
                        s_cqi = ((CellSignalStrengthLte) cellStrengthList.get(0)).getCqi(); // CQI
                    }
                }

                CellIdentityLte lteCellId;
                CellSignalStrengthLte lteSigStrength;
                boolean isServingCell = false;
                int numLTE = 0;
                List<CellInfo> cellInfoList = mTelephonyManager.getAllCellInfo();
                for (int i = 0; i < cellInfoList.size(); i++) {
                    if (cellInfoList.get(i).getClass().toString().equals("class android.telephony.CellInfoLte"))
                        numLTE++;
                }

                pData = new long[numLTE][11];
                for (int i = 0; i < cellInfoList.size(); i++) {
                    // Filter out non-LTE networks
                    if (cellInfoList.get(i).getClass().toString().equals("class android.telephony.CellInfoLte")) {
                        lteCellId = ((CellInfoLte) cellInfoList.get(i)).getCellIdentity();
                        lteSigStrength = ((CellInfoLte) cellInfoList.get(i)).getCellSignalStrength();
                        isServingCell = ((CellInfoLte) cellInfoList.get(i)).isRegistered();

                        // Update pData
                        pData[i][0] = ((lteCellId.getMccString() != null) && (lteCellId.getMncString() != null)) ?
                                Integer.parseInt(lteCellId.getMccString() + lteCellId.getMncString()) : MAX_VAL; // MCC + MNC
                        pData[i][1] = lteCellId.getTac(); // TAC
                        pData[i][2] = lteCellId.getPci(); // Physical Cell ID
                        pData[i][3] = lteCellId.getEarfcn(); // EARFCN
                        pData[i][4] = (isServingCell) ? s_rsrp : lteSigStrength.getRsrp(); // RSRP
                        pData[i][5] = (isServingCell) ? s_rsrq : lteSigStrength.getRsrq(); // RSRQ
                        pData[i][6] = (isServingCell) ? s_rssi : lteSigStrength.getRssi(); // RSSI
                        pData[i][7] = (isServingCell) ? s_rssnr : lteSigStrength.getRssnr(); // RSSNR
                        pData[i][8] = (isServingCell) ? s_cqi : lteSigStrength.getCqi(); // CQI
                        pData[i][9] = lteCellId.getBandwidth(); // BW_kHz
                        pData[i][10] = (isServingCell) ? 1 : 0; // Serving cell

                        if (MainActivity._DEBUG_ && isServingCell) {
                            System.out.println("SERVING CELL: (RSRP=" + s_rsrp + ", " + lteSigStrength.getRsrp() + ")");
                            System.out.println("SERVING CELL: (RSRP=" + s_rsrq + ", " + lteSigStrength.getRsrq() + ")");
                            System.out.println("SERVING CELL: (RSSNR=" + s_rssnr + ", " + lteSigStrength.getRssnr() + ")");
                            System.out.println("SERVING CELL: (RSRP=" + s_cqi + ", " + lteSigStrength.getCqi() + ")");
                        }
                    }
                }
            }
        }

    }

    public void createMeasurementReport() {
        if ((pData == null) || (pData.length == 0)) {
            if (MainActivity._DEBUG_) System.err.println("ERROR [PassiveMeasurementTest]: Empty");
            measurementReport = "";
            return;
        }

        for (int i = 0; i < pData.length; i++) {
            measurementReport = currTime + "," + ThreadManager.latitude + "," + ThreadManager.longitude +
                    "," + pData[i][0] + "," + pData[i][1] + "," + pData[i][2] + "," + pData[i][3] +
                    "," + pData[i][4] + "," + pData[i][5] + "," + pData[i][6] +
                    "," + pData[i][7] + "," + pData[i][8] + "," + pData[i][9] + "," + pData[i][10];
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    @Override
    public void initCSVFile() {
        String csvFileName = "pCell_Data_" + start_time + ".csv";
        String mimeType = "text/csv";

        if (MainActivity._DEBUG_) System.out.println("Writing measurements out to files: " + csvFileName);

        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, csvFileName);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

        ContentResolver resolver = context.getContentResolver();//.get().getContentResolver();
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
        if (uri != null) {
            try {
                osw = new OutputStreamWriter(resolver.openOutputStream(uri));
                osw.write("timestamp,lat,long,mcc-mnc,tac,pci,earfcn,rsrp,rsrq,rssi,rssnr,cqi,bw_khz,isServingCell" + "\n");
            } catch (Exception fnfe) {
                fnfe.printStackTrace();
            }
        }
    }

    @Override
    public void writeToCSV(String measurementReport) {
        if (MainActivity._DEBUG_) System.out.println("[" + _TAG_ + "] writeToCSV: Time = " + currTime + ", Measurement report = " + measurementReport);
        try {
            osw.write(measurementReport + "\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void closeCSVFile() {
        // Close CSV files for passive and active measurement data
        if (MainActivity._DEBUG_) System.out.println("[" + _TAG_ + "] Closing Output Files.");
        try {
            osw.flush();
            osw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (MainActivity._DEBUG_) System.out.println("[" + _TAG_ + "] EXITING. CLOSING FILES.");
    }

    private void processWait(long ms)
    {
        try { Thread.sleep(ms); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}
