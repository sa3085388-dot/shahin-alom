package com.example.ui

import android.annotation.SuppressLint
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LeafletMapWidget(
    lat: Double,
    lng: Double,
    targetLat: Double?,
    targetLng: Double?,
    targetName: String?,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            try {
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    loadDataWithBaseURL(
                        "https://app.local",
                        getMapHtml(),
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            } catch (e: Exception) {
                Log.e("LeafletMapWidget", "Failed to create WebView", e)
                WebView(context)
            }
        },
        update = { webView ->
            try {
                val hasTarget = targetLat != null && targetLng != null
                val tLat = targetLat ?: 0.0
                val tLng = targetLng ?: 0.0
                val safeTargetName = (targetName ?: "Target")
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", " ")
                
                webView.evaluateJavascript(
                    "if(typeof updateLocations === 'function') { updateLocations($lat, $lng, $tLat, $tLng, $hasTarget, '$safeTargetName'); }",
                    null
                )
            } catch (e: Exception) {
                Log.e("LeafletMapWidget", "Error updating map script", e)
            }
        }
    )
}

private fun getMapHtml(): String {
    return """
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
        <style>
            html, body, #map { width: 100%; height: 100%; margin: 0; padding: 0; background: #000; }
            .leaflet-control-layers { background: rgba(0, 0, 0, 0.8) !important; color: #fff !important; border-radius: 8px; border: none !important; font-family: sans-serif; font-size: 12px; }
            .leaflet-control-layers-toggle { filter: invert(1); }
            .leaflet-container { background: #111; }
        </style>
    </head>
    <body>
        <div id="map"></div>
        <script>
            // Google Hybrid Satellite Layer (Satellite + Street Names + Building Numbers + Landmarks)
            var googleHybrid = L.tileLayer('https://mt{s}.google.com/vt/lyrs=y&x={x}&y={y}&z={z}', {
                subdomains: ['0', '1', '2', '3'],
                maxZoom: 21,
                attribution: 'Google Hybrid Satellite'
            });

            // Google Standard Map
            var googleRoads = L.tileLayer('https://mt{s}.google.com/vt/lyrs=m&x={x}&y={y}&z={z}', {
                subdomains: ['0', '1', '2', '3'],
                maxZoom: 21,
                attribution: 'Google Maps'
            });

            // Esri Satellite + Detailed Transportation & Place Overlay
            var esriSat = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', { maxZoom: 19 });
            var esriTrans = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}', { maxZoom: 19 });
            var esriPlaces = L.tileLayer('https://services.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}', { maxZoom: 19 });
            var esriGroup = L.layerGroup([esriSat, esriTrans, esriPlaces]);

            var map = L.map('map', {
                zoomControl: false,
                layers: [googleHybrid] // Default to Google Hybrid Satellite with full street/building labels
            }).setView([23.8103, 90.4125], 14);

            L.control.zoom({position: 'bottomright'}).addTo(map);

            var baseMaps = {
                "🛰️ Google Satellite (বাসা/রাস্তার নাম সহ)": googleHybrid,
                "🗺️ Google Standard Map": googleRoads,
                "🌍 Esri World Satellite": esriGroup
            };
            L.control.layers(baseMaps, null, {position: 'topright'}).addTo(map);

            var myIcon = L.divIcon({
                className: 'my-pin',
                html: '<div style="background:#00E676;width:22px;height:22px;border-radius:50%;border:3px solid #fff;box-shadow:0 0 12px #00E676;"></div>',
                iconSize: [22, 22],
                iconAnchor: [11, 11]
            });

            var targetIcon = L.divIcon({
                className: 'target-pin',
                html: '<div style="background:#FF1744;width:26px;height:26px;border-radius:50%;border:3px solid #fff;box-shadow:0 0 14px #FF1744;"></div>',
                iconSize: [26, 26],
                iconAnchor: [13, 13]
            });

            var myMarker = L.marker([23.8103, 90.4125], {icon: myIcon}).addTo(map).bindPopup("You (আমি)");
            var targetMarker = null;
            var polyline = null;
            var initialLoad = true;

            function updateLocations(myLat, myLng, targetLat, targetLng, hasTarget, targetName) {
                myMarker.setLatLng([myLat, myLng]);
                
                if (initialLoad) {
                    map.setView([myLat, myLng], 15);
                    initialLoad = false;
                }

                if (hasTarget) {
                    var targetLatLng = [targetLat, targetLng];
                    if (!targetMarker) {
                        targetMarker = L.marker(targetLatLng, {icon: targetIcon}).addTo(map).bindPopup(targetName);
                    } else {
                        targetMarker.setLatLng(targetLatLng);
                        targetMarker.bindPopup(targetName);
                    }

                    if (!polyline) {
                        polyline = L.polyline([[myLat, myLng], targetLatLng], {color: '#00E676', weight: 6, opacity: 0.9, dashArray: '8, 8'}).addTo(map);
                    } else {
                        polyline.setLatLngs([[myLat, myLng], targetLatLng]);
                    }
                } else if (targetMarker) {
                    map.removeLayer(targetMarker);
                    targetMarker = null;
                    if (polyline) {
                        map.removeLayer(polyline);
                        polyline = null;
                    }
                }
            }
        </script>
    </body>
    </html>
    """
}
