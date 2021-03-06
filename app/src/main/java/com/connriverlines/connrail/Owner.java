package com.connriverlines.connrail;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.PowerManager;

import com.connriverlines.connrail.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Timer;
import java.util.TimerTask;

import static android.content.Context.WIFI_SERVICE;

/**
 * Created by bbrown on 7/6/2018
 */

class Owner {
    private ServerSocket mOwnerSocket = null;
    private static final ArrayList<Socket> listSockets = new ArrayList<>();
    private OnDataUpdate mCallback = null;
    private final Timer timer;
    private final PowerManager.WakeLock wl;
    private final WifiManager.WifiLock wfl;
    private static final int PING_INTERVAL = 5000;

    // start a thread that listens on a port for new remote socket connections
    // for each new socket listen for a message

    Owner(OnDataUpdate callback, Context context) {

        mCallback = callback;

        // the owner has to stay alive during sleep mode
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "keep_app_alive");
        wl.acquire();

        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(WIFI_SERVICE);
        wfl = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "keep_wifi_alive");
        wfl.acquire();

        // start the connected timer that sends pings to any listening remotes
        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                // send a ping to all attached remotes
                sendAll(MainActivity.MSG_PING, "");

                // send a ping to the UI so it can display the remote count
                if (mCallback != null) {
                    mCallback.onOwnerDataUpdate(MainActivity.MSG_PING, "");
                }
            }
        }, 0, PING_INTERVAL);

        // create a new worker thread and start
        Thread newSocketThread = new Thread(new NewSocketThread());
        newSocketThread.start();
    }

    void setCallback(OnDataUpdate callback) {
        mCallback = callback;
    }

    void close() {

        timer.cancel();

        wfl.release();
        wl.release();

        // close any the remote sockets
        for (Socket socket : listSockets) {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // close the owner socket - this will trigger an exception in the accept thread and end it
        if (mOwnerSocket != null) {
            try {
                mOwnerSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private class NewSocketThread extends Thread {

        @Override
        public void run() {
            try {
                // create ServerSocket using specified port
                mOwnerSocket = new ServerSocket(MainActivity.SOCKET_PORT);

                while (true) {
                    // wait for a new socket connection
                    Socket socket = mOwnerSocket.accept();

                    // add it to the list of available sockets
                    addSocket(socket);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void addSocket(Socket socket) {
        // add the socket to the list
        for (Socket sx : listSockets) {
            if (sx.getInetAddress().equals(socket.getInetAddress())) {
                try {
                    sx.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                listSockets.remove(sx);
            }
        }
        listSockets.add(socket);

        // start a thread to listen for any data from the remote
        Thread listenThread = new Thread(new ListenThread(socket));
        listenThread.start();

        // send the new remote the initial data
        send(socket, MainActivity.MSG_PING, "");
    }

    private class ListenThread extends Thread {

        final Socket mSocket;
        ListenThread(Socket socket) {
            mSocket = socket;
        }

        @Override
        public void run() {
            try {
                //create a new socket, get the input stream and listen
                DataInputStream dis = new DataInputStream(mSocket.getInputStream());
                String sData;

                while (true) {
                    sData = dis.readUTF();

                    int iMsgType = 0;
                    String sMsgData = null;
                    try {
                        final JSONObject jsonData;
                        jsonData = new JSONObject(sData);

                        iMsgType = jsonData.getInt(MainActivity.MSG_TYPE_TAG);
                        sMsgData = jsonData.getString(MainActivity.MSG_DATA_TAG);

                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                    handleRemoteMsg(mSocket, iMsgType, sMsgData);
                }


            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // handle receiving a message from a remote
    private void handleRemoteMsg(Socket socket, int iMsgType, String sMsgData) {

        switch (iMsgType) {
            case MainActivity.MSG_REQUEST_SESSION_DATA:
                send(socket, MainActivity.MSG_SESSION_DATA, String.valueOf(MainActivity.getSessionNumber()));
                return;
            case MainActivity.MSG_REQUEST_FULL_DATA:
                sendAllTables(socket);
                return;

            case MainActivity.MSG_DELETE_SPOT_DATA:
                try {
                    MainActivity.spotAddEditDelete(new SpotData(new JSONObject(sMsgData)), true);
                } catch (JSONException e) {
                    e.printStackTrace();
                }

                break;

            case MainActivity.MSG_UPDATE_SPOT_DATA:
                try {
                    MainActivity.spotAddEditDelete(new SpotData(new JSONObject(sMsgData)), false);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                break;

            case MainActivity.MSG_DELETE_CONSIST_DATA:
                try {
                    MainActivity.consistAddEditDelete(new ConsistData(new JSONObject(sMsgData)), true);
                } catch (JSONException e) {
                    e.printStackTrace();
                }

                break;

            case MainActivity.MSG_UPDATE_CONSIST_DATA:
                try {
                    MainActivity.consistAddEditDelete(new ConsistData(new JSONObject(sMsgData)), false);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                break;

            case MainActivity.MSG_DELETE_CAR_DATA:
                try {
                    MainActivity.carAddEditDelete(new CarData(new JSONObject(sMsgData)), true);
                } catch (JSONException e) {
                    e.printStackTrace();
                }

                break;

            case MainActivity.MSG_UPDATE_CAR_DATA:
                try {
                    MainActivity.carAddEditDelete(new CarData(new JSONObject(sMsgData)), false);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                break;
        }

        // pass the message on to the UI unless a return from switch above
        if (mCallback != null) {
            mCallback.onOwnerDataUpdate(iMsgType, sMsgData);
        }
    }

    // send all the tables to a given remote/socket
    private void sendAllTables(Socket socket) {
        send(socket, MainActivity.MSG_FULL_SPOT_DATA, buildSpotTable());
        send(socket, MainActivity.MSG_FULL_CONSIST_DATA, buildConsistTable());
        send(socket, MainActivity.MSG_FULL_CAR_DATA, buildCarTable());
    }

    private String buildSpotTable() {
        JSONArray jArray = new JSONArray();
        for (SpotData sd : MainActivity.getSpotList()) {
            jArray.put(sd.toJSON());
        }
        return jArray.toString();
    }

    private String buildConsistTable() {
        JSONArray jArray = new JSONArray();
        for (ConsistData cd : MainActivity.getConsistList()) {
            jArray.put(cd.toJSON());
        }
        return jArray.toString();
    }

    private String buildCarTable() {
        JSONArray jArray = new JSONArray();
        for (CarData cd : MainActivity.getCarList()) {
            jArray.put(cd.toJSON());
        }
        return jArray.toString();
    }

    private String buildMessage(int iMsgType, String sData) {
        JSONObject jsonData = new JSONObject();
        try {
            jsonData.put(MainActivity.MSG_TYPE_TAG, iMsgType);
            jsonData.put(MainActivity.MSG_DATA_TAG, sData);
            return jsonData.toString();
        } catch (JSONException e) {
            e.printStackTrace();
            return null;
        }
    }

    // send to a specific remote
    private void send(Socket socket, int msgType, String data) {
        SendSocket ss = new SendSocket(socket, buildMessage(msgType, data));
        ss.execute();
    }

    private class SendSocket extends AsyncTask<Void, Void, Void> {

        private final String sOut;
        private Socket targetSocket = null;

        public SendSocket(Socket socket, String sx) {
            targetSocket = socket;
            sOut = sx;
        }

        @Override
        protected Void doInBackground(Void... params) {
            try {
                DataOutputStream dos = new DataOutputStream(targetSocket.getOutputStream());
                dos.writeUTF(sOut);
            } catch (IOException e) {
                e.printStackTrace();
            }

            return null;
        }
    }

    // send the update to all remotes
    void sendAll(int msgType, String data) {
        SendAllSockets sas = new SendAllSockets(buildMessage(msgType, data));
        sas.execute();
    }

    private class SendAllSockets extends AsyncTask<Void, Void, Void> {

        private final String sOut;

        SendAllSockets(String sx) {
            sOut = sx;
        }

        @Override
        protected Void doInBackground(Void... params) {
            for (Socket sx : listSockets) {
                try {
                    DataOutputStream dos = new DataOutputStream(sx.getOutputStream());
                    dos.writeUTF(sOut);
                } catch (IOException e) {
                    e.printStackTrace();
                    listSockets.remove(sx);
                }
            }

            return null;
        }
    }

    String getIP(Context context) {
        String ret = findIP_WIFI(context);
        if (ret == null) {
            ret = findIP_Internet(context);
        }
        return ret;
    }

    int getRemoteCount() {
        return listSockets.size();
    }

    private String findIP_WIFI(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(WIFI_SERVICE);
        int ipAddress = wifiManager.getConnectionInfo().getIpAddress();

        // Convert little-endian to big-endian if needed
        if (ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN)) {
            ipAddress = Integer.reverseBytes(ipAddress);
        }

        byte[] ipByteArray = BigInteger.valueOf(ipAddress).toByteArray();

        String ipAddressString;
        try {
            ipAddressString = InetAddress.getByAddress(ipByteArray).getHostAddress();
        } catch (UnknownHostException ex) {
            ipAddressString = null;
        }

        return ipAddressString;
    }

    private String findIP_Internet(Context context) {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return context.getString(R.string.not_found);
    }


    interface OnDataUpdate {
        void onOwnerDataUpdate(int msgType, String sData);
    }

}
