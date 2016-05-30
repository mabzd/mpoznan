package com.kucware.mpoznan;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.FragmentActivity;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public class MapActivity extends FragmentActivity implements OnMapReadyCallback {

    private final int BIKES_WARN = 3;
    private final int BIKES_ERROR = 0;
    private final String BIKE_STATIONS_URI = "https://nextbike.net/maps/nextbike-official.xml?city=192";
    private final int REFRESH_SEC = 30;

    private GoogleMap map;
    private Handler handler;
    private Timer timer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        map = googleMap;
        timer = new Timer();

        handler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                List<BikeStation> stations = (List<BikeStation>) msg.obj;

                if (stations == null) {
                    toastError();
                    return;
                }
                if (stations.isEmpty()) {
                    toastNoStations();
                    return;
                }

                map.clear();
                markBikeStations(stations);
            }
        };

        TimerTask task = new TimerTask() {
            public void run() {
                Message msg = handler.obtainMessage();
                msg.obj = getBikeStations();
                handler.sendMessage(msg);
            }
        };

        timer.schedule(task, 0, REFRESH_SEC * 1000);

        map.moveCamera(CameraUpdateFactory.newLatLng(new LatLng(52.4077, 16.9323)));
        map.animateCamera(CameraUpdateFactory.zoomTo(13.0f), 2000, null);
        enableMyLocation();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (timer != null) {
            timer.cancel();
            timer.purge();
        }
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }

    private void enableMyLocation() {
        String fine = Manifest.permission.ACCESS_FINE_LOCATION;
        String coarse = Manifest.permission.ACCESS_COARSE_LOCATION;
        int granted = PackageManager.PERMISSION_GRANTED;

        if (ActivityCompat.checkSelfPermission(this, fine) != granted
                && ActivityCompat.checkSelfPermission(this, coarse) != granted) {
            toastLocationPerm();
            return;
        }

        map.setMyLocationEnabled(true);
    }


    private List<BikeStation> getBikeStations() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(BIKE_STATIONS_URI);
            conn = (HttpURLConnection) url.openConnection();
            InputStream stream = new BufferedInputStream(conn.getInputStream());
            return parseBikeStationsXml(stream);
        } catch (Exception e) {
            Log.e("GetBikeStations", "Error obtaining bike stations from REST service", e);
        } finally {
            if (conn != null)
                conn.disconnect();
        }

        return null;
    }

    private void markBikeStations(List<BikeStation> stations) {
        for (BikeStation station : stations) {
            MarkerOptions marker = getMarker(station);
            map.addMarker(marker);
        }
    }

    private void toastError() {
        Toast
                .makeText(
                    getApplicationContext(),
                    getString(R.string.fetchErrorText, REFRESH_SEC),
                    Toast.LENGTH_LONG)
                .show();
    }

    private void toastNoStations() {
        Toast
                .makeText(
                        getApplicationContext(),
                        getString(R.string.noStationsText, REFRESH_SEC),
                        Toast.LENGTH_LONG)
                .show();
    }

    private void toastLocationPerm() {
        Toast
                .makeText(
                        getApplicationContext(),
                        R.string.locationPermText,
                        Toast.LENGTH_LONG)
                .show();
    }

    private List<BikeStation> parseBikeStationsXml(InputStream stream) {
        try {
            List<BikeStation> stations = new ArrayList<>();
            Document doc = loadXmlDocument(stream);
            Element root = doc.getDocumentElement();
            if (root != null) {
                NodeList places = root.getElementsByTagName("place");
                if (places != null) {
                    for (int i = 0; i < places.getLength(); i++) {
                        Node node = places.item(i);
                        if (node != null && node.getNodeType() == Node.ELEMENT_NODE) {
                            Element place = (Element)node;
                            String lat = place.getAttribute("lat");
                            String lng = place.getAttribute("lng");
                            String name = place.getAttribute("name");
                            String bikes = place.getAttribute("bikes");
                            String racks = place.getAttribute("free_racks");
                            BikeStation station = parseBikeStation(lat, lng, name, bikes, racks);
                            if (station != null)
                                stations.add(station);
                        }
                    }
                }
            }
            return stations;
        } catch (Exception e) {
            Log.e("ParseBikeStationsXml", "Error when parsing bike stations XML", e);
        }

        return null;
    }

    private BikeStation parseBikeStation(
            String lat,
            String lng,
            String name,
            String bikes,
            String racks) {
        BikeStation station = new BikeStation();

        try {
            station.setLat(Double.parseDouble(lat));
            station.setLng(Double.parseDouble(lng));
            station.setName(name == null ? "?" : name);
            station.setBikes(parseNullableIntStr(bikes));
            station.setRacks(parseNullableIntStr(racks));
            return station;
        } catch (Exception e) {
            String errorMsg = String.format(
                    "Cannot parse bike station: %s, %s, %s, %s, %s",
                    lat, lng, name, bikes, racks);
            Log.i("Parse Bike Station", errorMsg, e);
        }

        return null;
    }

    private Integer parseNullableIntStr(String intStr) {
        if (intStr == null || intStr.isEmpty())
            return null;
        try {
            // There can be entries with + sign at the end (fe. '5+' bikes). Get rid of it.
            intStr = intStr.trim().replaceAll("\\++$", "");
            return new Integer(intStr);
        } catch (Exception e) {
            return null;
        }
    }

    private MarkerOptions getMarker(BikeStation station) {

        int resourceId = getMarkerBitmapId(station.getBikes());
        Bitmap markerBitmap = BitmapFactory.decodeResource(getResources(), resourceId);

        Bitmap bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawBitmap(markerBitmap, 0, 0, null);

        Paint textPaint = new Paint();
        textPaint.setTextSize(14);
        textPaint.setColor(getMarkerColor(station.getBikes()));
        textPaint.setAntiAlias(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
        int xPos = canvas.getWidth() / 2;
        int yPos = (int) (42 - ((textPaint.descent() + textPaint.ascent()) / 2));
        canvas.drawText(getNullableIntString(station.getBikes()), xPos, yPos, textPaint);

        LatLng latlng = new LatLng(station.getLat(), station.getLng());
        return new MarkerOptions()
                .position(latlng)
                .title(station.getName())
                .snippet(getSnippet(station.getBikes(), station.getRacks()))
                .icon(BitmapDescriptorFactory.fromBitmap(bmp))
                .anchor(0.5f, 1.0f);
    }

    private static Document loadXmlDocument(InputStream stream) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(stream);
    }

    private int getMarkerBitmapId(Integer bikes) {
        if (bikes == null)
            return R.drawable.bikemark64n;
        if (bikes > BIKES_WARN)
            return R.drawable.bikemark64b;
        if (bikes > BIKES_ERROR)
            return R.drawable.bikemark64y;
        return R.drawable.bikemark64r;
    }

    private int getMarkerColor(Integer bikes) {
        if (bikes == null)
            return Color.rgb(100, 100, 100);
        if (bikes > BIKES_WARN)
            return Color.rgb(0, 100, 160);
        if (bikes > BIKES_ERROR)
            return Color.rgb(160, 100, 0);
        return Color.rgb(160, 0, 0);
    }

    private String getSnippet(Integer bikes, Integer racks) {
        String bikesText = getNullableIntString(bikes);
        String rakesText = getNullableIntString(racks);
        return getString(R.string.snippetText, bikesText, rakesText);
    }

    private String getNullableIntString(Integer i) {
        return i == null ? "?" : i.toString();
    }

}
