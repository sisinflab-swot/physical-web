package it.poliba.sisinflab.psw;

import android.app.DownloadManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.support.v4.app.NotificationCompat;

import org.physical_web.collection.PwPair;
import org.physical_web.collection.PwsResult;
import org.physical_web.collection.PwsResultCallback;
import org.physical_web.collection.PwsResultIconCallback;
import org.physical_web.collection.UrlDevice;
import org.physical_web.physicalweb.BleUrlDeviceDiscoverer;
import org.physical_web.physicalweb.ConnectionListener;
import org.physical_web.physicalweb.Log;
import org.physical_web.physicalweb.R;
import org.physical_web.physicalweb.UrlDeviceDiscoveryService;
import org.physical_web.physicalweb.Utils;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import it.poliba.sisinflab.psw.ble.PswUidBluetoothGattCallback;

public class PswDeviceDiscoveryService extends UrlDeviceDiscoveryService {

    private static final String TAG = PswDeviceDiscoveryService.class.getSimpleName();
    private PswUidBluetoothGattCallback bleGatt;

    /**
     * Binder class for getting connections to the service.
     */
    public class PswLocalBinder extends Binder {
        public PswDeviceDiscoveryService getServiceInstance() {
            return PswDeviceDiscoveryService.this;
        }
    }

    public PswDeviceDiscoveryService() {
        mBinder = new PswLocalBinder();
    }

    @Override
    protected void initialize() {
        super.initialize();
        //if (PswUtils.isPswEnabled(this)) {

            // Remove basic BleUrlDeviceDiscoverer
            for(int i=0; i<mUrlDeviceDiscoverers.size(); i++) {
                if (mUrlDeviceDiscoverers.get(i) instanceof BleUrlDeviceDiscoverer){
                    mUrlDeviceDiscoverers.remove(i);
                    break;
                }
            }

            // Add PswBleDeviceDiscoverer
            Log.d(TAG, "psw started");
            PswBleDeviceDiscoverer mPDD = new PswBleDeviceDiscoverer(this);
            mPDD.setCallback(this);
            mUrlDeviceDiscoverers.add(mPDD);

            bleGatt = new PswUidBluetoothGattCallback(getBaseContext());
        //}
    }

    @Override
    public void onUrlDeviceDiscovered(UrlDevice urlDevice) {
            try {
                if (urlDevice.getExtraString(PswUtils.TYPE_KEY).equals(PswUtils.PSW_URL_DEVICE_TYPE)) {
                    if (PswUtils.isPswEnabled(getBaseContext()))
                        this.onPswUrlDeviceDiscovered(urlDevice);
                    else
                        getPwCollection().removeUrlDevice(urlDevice);
                } else if (urlDevice.getExtraString(PswUtils.TYPE_KEY).equals(PswUtils.PSW_UID_DEVICE_TYPE)) {
                    if (PswUtils.isPswEnabled(getBaseContext()))
                        this.onPswUidDeviceDiscovered(urlDevice);
                    else
                        getPwCollection().removeUrlDevice(urlDevice);
                } else if (urlDevice.getUrl().length()<=30)
                    super.onUrlDeviceDiscovered(urlDevice);
            } catch (Exception e) {
                Log.e(TAG, "Beacon Type not found!");
                e.printStackTrace();
            }
    }

    private void onPswUidDeviceDiscovered(UrlDevice urlDevice) {
        Log.d(TAG, urlDevice.getUrl() + " [PSW-UID]");
        mPwCollection.addUrlDevice(urlDevice);
        mPwCollection.addMetadata(new PwsResult.Builder(urlDevice.getUrl(), urlDevice.getUrl())
                .setTitle(Utils.getTitle(urlDevice))
                .setDescription(Utils.getDescription(urlDevice))
                .build());
        triggerCallback();

        String filename = PswUtils.getPswUidFragmentName(urlDevice);
        File owl = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString() + "/" + filename + ".owl");
        if (!owl.exists() || PswUtils.isObsolete(owl, getBaseContext())) {
            if (!bleGatt.isRunning()) {
                bleGatt.connect(urlDevice.getUrl(), filename, new PswUidConnectionListener(filename, urlDevice.getUrl()));
            }
        } else {
            PwsResult pwsResult = mPwCollection.getMetadataByBroadcastUrl(urlDevice.getUrl());
            if (pwsResult != null && PswUtils.getResourceIRI(pwsResult) == null) {
                refreshPSWData(owl, pwsResult, false);
            } else
                triggerCallback();
        }
    }

    private class PswUidConnectionListener implements ConnectionListener {
        String mFile;
        String mUrl;

        public PswUidConnectionListener(String filename, String url) {
            mFile = filename;
            mUrl = url;
        }

        @Override
        public void onConnectionFinished() {
            Log.d(TAG, "PSW-UID download. TaskCompleted!");

            File owl = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString() + "/" + mFile + ".owl");
            if (owl != null && owl.length()>0) {
                NotificationCompat.Builder mBuilder =
                        new NotificationCompat.Builder(getBaseContext())
                                .setSmallIcon(R.drawable.ic_notification)
                                .setAutoCancel(true)
                                .setNumber(5)
                                .setGroup("PSW-UID")
                                .setContentTitle("PSW-UID Beacon")
                                .setContentText("OWL fragment downloaded!");
                NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                mNotificationManager.notify(TAG, 0, mBuilder.build());
            } else {
                Log.e(TAG, "PSW-UID OWL file not found!");
                NotificationCompat.Builder mBuilder =
                        new NotificationCompat.Builder(getBaseContext())
                                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                                .setAutoCancel(true)
                                .setNumber(5)
                                .setGroup("PSW-UID")
                                .setContentTitle("PSW-UID Beacon")
                                .setContentText("Download failed. Please retry!");
                NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                mNotificationManager.notify(TAG, 0, mBuilder.build());
                return;
            }

            PwsResult pwsResult = mPwCollection.getMetadataByBroadcastUrl(mUrl);
            if (pwsResult != null && PswUtils.getResourceIRI(pwsResult) == null)
                refreshPSWData(owl, pwsResult, true);
            else
                triggerCallback();

        }
    }

    private void onPswUrlDeviceDiscovered(UrlDevice urlDevice) {
        mPwCollection.addUrlDevice(urlDevice);
        Log.d(TAG, urlDevice.getUrl() + " [PSW-URL]");
        mPwCollection.fetchPwsResults(new PwsResultCallback() {
            long mPwsTripTimeMillis = 0;

            @Override
            public void onPwsResult(PwsResult pwsResult) {
                PwsResult replacement = new Utils.PwsResultBuilder(pwsResult)
                    .setPwsTripTimeMillis(pwsResult, mPwsTripTimeMillis)
                    .setTitle(Utils.getTitle(urlDevice))
                    .setDescription(Utils.getDescription(urlDevice))
                    .build();
                addPSWMetadata(pwsResult);
                mPwCollection.addMetadata(replacement);
                triggerCallback();
                updateNotifications();
            }

            @Override
            public void onPwsResultAbsent(String url) {
                triggerCallback();
            }

            @Override
            public void onPwsResultError(Collection<String> urls, int httpResponseCode, Exception e) {
                Log.d(TAG, "PwsResultError: " + httpResponseCode + " ", e);
                triggerCallback();
            }

            @Override
            public void onResponseReceived(long durationMillis) {
                mPwsTripTimeMillis = durationMillis;
            }
        }, new PwsResultIconCallback() {
            @Override
            public void onIcon(byte[] icon) {
                triggerCallback();
            }

            @Override
            public void onError(int httpResponseCode, Exception e) {
                Log.d(TAG, "PwsResultError: " + httpResponseCode + " ", e);
                triggerCallback();
            }
        });

        PwsResult pwsResult = mPwCollection.getMetadataByBroadcastUrl(urlDevice.getUrl());
        if (pwsResult != null)
            addPSWMetadata(pwsResult);
        else
            triggerCallback();
    }

    private void refreshPSWData(File owl, PwsResult pwsResult, boolean loadInKB) {
        PwsResult replacement = PswUtils.getPSWResult(owl, pwsResult, loadInKB);
        mPwCollection.addMetadata(replacement);
        triggerCallback();
        updateNotifications();
    }

    private long mDownloadedFileID = -1;
    private boolean downloading = false;

    private File getOWLFile(String name) {
        for(File f : Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).listFiles()) {
            if (f.getName().startsWith(name))
                return f;
        }
        return null;
    }

    private void addPSWMetadata(PwsResult pwsResult) {
        Uri url = Uri.parse(pwsResult.getRequestUrl());
        //File owl = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString() + "/" + url.getLastPathSegment() + ".owl");
        File owl = getOWLFile(url.getLastPathSegment());

        if (owl != null && !PswUtils.isObsolete(owl, getBaseContext())) {
            // load file previously downloaded
            refreshPSWData(owl, pwsResult, false);
        } else if (!downloading) {
            // get download service and enqueue file
            final DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(url);
            request.setDescription("Downloading Beacon annotation...");
            request.setTitle("PSW-URL Beacon");
            request.allowScanningByMediaScanner();
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, url.getLastPathSegment() + ".owl");

            final Context c = this.getBaseContext();
            mDownloadedFileID = -1;

            BroadcastReceiver onComplete = new BroadcastReceiver() {
                public void onReceive(Context ctxt, Intent intent) {
                    Log.i(TAG, pwsResult.getRequestUrl() + " downloaded!");
                    downloading = false;

                    if (mDownloadedFileID == -1)
                        return;
                    else {
                        Uri uri = manager.getUriForDownloadedFile(mDownloadedFileID);
                        if (uri != null) {
                            /*String mostRecentDownload = uri.getLastPathSegment();
                            if (owl.getAbsolutePath().contains(mostRecentDownload)) {
                                refreshPSWData(owl, pwsResult, true);
                            }*/

                            File tmp = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString() + "/" + url.getLastPathSegment() + ".owl");
                            refreshPSWData(tmp, pwsResult, true);
                        }
                    }

                    c.unregisterReceiver(this);
                }
            };

            c.registerReceiver(onComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
            mDownloadedFileID = manager.enqueue(request);
            downloading = true;
        }
    }

    protected List<PwPair> getNotBlockedPwPairs(Context context) {
        List<PwPair> pwPairs = mPwCollection.getGroupedPwPairsSortedByRank(
            new PswUtils.PwPairSemanticBasedComparator(context));
        List<PwPair> notBlockedPwPairs = new ArrayList<>();
        for (PwPair i : pwPairs) {
            if (!Utils.isBlocked(i)) {
                notBlockedPwPairs.add(i);
            }
        }
        return notBlockedPwPairs;
    }

}