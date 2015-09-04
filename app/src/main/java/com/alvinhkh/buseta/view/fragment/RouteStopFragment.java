package com.alvinhkh.buseta.view.fragment;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.alvinhkh.buseta.Constants;
import com.alvinhkh.buseta.R;
import com.alvinhkh.buseta.database.FavouriteDatabase;
import com.alvinhkh.buseta.holder.RouteBound;
import com.alvinhkh.buseta.holder.RouteStop;
import com.alvinhkh.buseta.view.adapter.RouteStopAdapter;
import com.alvinhkh.buseta.holder.RouteStopETA;
import com.alvinhkh.buseta.holder.RouteStopMap;
import com.alvinhkh.buseta.preference.SettingsHelper;
import com.alvinhkh.buseta.view.dialog.RouteEtaDialog;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.ion.Ion;
import com.koushikdutta.ion.Response;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RouteStopFragment extends Fragment
        implements AdapterView.OnItemClickListener,
        AdapterView.OnItemLongClickListener,
        SwipeRefreshLayout.OnRefreshListener {

    private static final String TAG = "RouteStopFragment";
    private static final String KEY_LIST_VIEW_STATE = "KEY_LIST_VIEW_STATE_ROUTE_STOP";

    private Context mContext = super.getActivity();
    private ActionBar mActionBar = null;
    private Menu mMenu = null;
    private SwipeRefreshLayout mSwipeRefreshLayout;
    private ListView mListView;
    private TextView mEmptyText;
    private ProgressBar mProgressBar;
    private RouteStopAdapter mAdapter;
    private UpdateViewReceiver mReceiver;

    private RouteBound _routeBound;
    private String _route_no = null;
    private String _route_bound = null;
    private String _route_origin = null;
    private String _route_destination = null;
    private String _id = null;
    private String _token = null;
    private String etaApi = "";
    private String getRouteInfoApi = "";
    private Boolean savedState = false;
    private SettingsHelper settingsHelper = null;
    private FavouriteDatabase mDatabase;

    // Runnable to get all stops eta
    int iEta = 0;
    Handler mEtaHandler = new Handler();
    Runnable mEtaRunnable = new Runnable() {
        @Override
        public void run() {
            if (null != mSwipeRefreshLayout)
                mSwipeRefreshLayout.setRefreshing(true);
            if (null != mAdapter && iEta < mAdapter.getCount()) {
                RouteStop routeStop = mAdapter.getItem(iEta);
                getETA(iEta, routeStop.code);
                iEta++;
                if (iEta < mAdapter.getCount() - 1) {
                    mEtaHandler.postDelayed(mEtaRunnable, 250);
                } else {
                    if (mSwipeRefreshLayout != null)
                        mSwipeRefreshLayout.setRefreshing(false);
                }
            }
        }
    };

    Handler mAutoRefreshHandler = new Handler();
    Runnable mAutoRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (null != mAdapter)
                mAdapter.notifyDataSetChanged();
            mAutoRefreshHandler.postDelayed(mAutoRefreshRunnable, 30 * 1000); // every half minute
        }
    };

    public RouteStopFragment() {
    }

    public static RouteStopFragment newInstance(RouteBound routeBound) {
        RouteStopFragment f = new RouteStopFragment();
        Bundle args = new Bundle();
        args.putParcelable("route", routeBound);
        f.setArguments(args);
        return f;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             final Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_routestop, container, false);
        mContext = super.getActivity();
        settingsHelper = new SettingsHelper().parse(mContext.getApplicationContext());
        // set database
        mDatabase = new FavouriteDatabase(mContext);
        // Get arguments
        _routeBound = getArguments().getParcelable("route");
        if (null != _routeBound) {
            _route_no = _routeBound.route_no.trim().replace(" ", "").toUpperCase();
            _route_bound = _routeBound.route_bound;
            _route_origin = _routeBound.origin_tc;
            _route_destination = _routeBound.destination_tc;
        }
        // Set Toolbar
        mActionBar = ((AppCompatActivity) getActivity()).getSupportActionBar();
        mActionBar.setTitle(_route_no);
        mActionBar.setSubtitle(getString(R.string.destination, _route_destination));
        mActionBar.setDisplayHomeAsUpEnabled(false);
        setHasOptionsMenu(true);
        // Set List Adapter
        mAdapter = new RouteStopAdapter(mContext);
        if (savedInstanceState != null) {
            mAdapter.onRestoreInstanceState(savedInstanceState);
            _id = savedInstanceState.getString("_id");
            _token = savedInstanceState.getString("_token");
            etaApi = savedInstanceState.getString("etaApi");
            getRouteInfoApi = savedInstanceState.getString("getRouteInfoApi");
        }
        //
        mSwipeRefreshLayout = (SwipeRefreshLayout) view.findViewById(R.id.swipe_refresh_route);
        mSwipeRefreshLayout.setOnRefreshListener(this);
        mSwipeRefreshLayout.setEnabled(false); // disable pull-to-refresh
        mSwipeRefreshLayout.setRefreshing(false);
        mProgressBar = (ProgressBar) view.findViewById(R.id.progressBar);
        mProgressBar.setVisibility(View.GONE);
        // Set Listview
        mListView = (ListView) view.findViewById(android.R.id.list);
        mListView.setDividerHeight(2);
        mEmptyText = (TextView) view.findViewById(android.R.id.empty);
        mEmptyText.setText("");
        mListView.setEmptyView(view.findViewById(R.id.empty));
        if (savedInstanceState != null
                && savedInstanceState.containsKey(KEY_LIST_VIEW_STATE)) {
            mListView.onRestoreInstanceState(savedInstanceState
                    .getParcelable(KEY_LIST_VIEW_STATE));
            savedState = true;
        } else {
            getRouteInfoApi = Constants.URL.ROUTE_INFO;
            // Get Route Stops
            getRouteStops(_route_no, _route_bound);
            if (settingsHelper.getEtaApi() == 1)
                findEtaApiUrl();
        }
        mListView.setAdapter(mAdapter);
        mListView.setOnItemLongClickListener(this);
        mListView.setOnItemClickListener(this);
        // Broadcast Receiver
        if (null != mContext) {
            IntentFilter mFilter = new IntentFilter(Constants.MESSAGE.STOP_UPDATED);
            mReceiver = new UpdateViewReceiver();
            mFilter.addAction(Constants.MESSAGE.STOP_UPDATED);
            mContext.registerReceiver(mReceiver, mFilter);
        }

        return view;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (null != mAdapter) {
            mAdapter.onSaveInstanceState(outState);
            outState.putParcelable(KEY_LIST_VIEW_STATE, mListView.onSaveInstanceState());
        }
        outState.putParcelable("route", _routeBound);
        outState.putString("_id", _id);
        outState.putString("_token", _token);
        outState.putString("etaApi", etaApi);
        outState.putString("getRouteInfoApi", getRouteInfoApi);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (null != mActionBar) {
            mActionBar.setTitle(_route_no);
            mActionBar.setSubtitle(getString(R.string.destination, _route_destination));
        }
        if (null != mAutoRefreshHandler && null != mAutoRefreshRunnable)
            mAutoRefreshHandler.post(mAutoRefreshRunnable);
    }

    @Override
    public void onPause() {
        if (null != mAutoRefreshHandler && null != mAutoRefreshRunnable)
            mAutoRefreshHandler.removeCallbacks(mAutoRefreshRunnable);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (null != mContext && null != mReceiver)
            mContext.unregisterReceiver(mReceiver);
        if (null != mSwipeRefreshLayout)
            mSwipeRefreshLayout.setRefreshing(false);
        if (null != mListView)
            mListView.setAdapter(null);
        if (null != mProgressBar)
            mProgressBar.setVisibility(View.GONE);
        if (null != mEmptyText)
            mEmptyText.setVisibility(View.GONE);
        if (null != mDatabase)
            mDatabase.close();
        View view = getView();
        if (null != view)
            view.setVisibility(View.GONE);
        if (null != mEtaHandler && null != mEtaRunnable)
            mEtaHandler.removeCallbacks(mEtaRunnable);
        if (null != mAutoRefreshHandler && null != mAutoRefreshRunnable)
            mAutoRefreshHandler.removeCallbacks(mAutoRefreshRunnable);
        Ion.getDefault(mContext).cancelAll(mContext);
        super.onDestroyView();
    }

    @Override
    public void onItemClick(AdapterView<?> parent, final View view,
                            final int position, long id) {
        if (view != null) {
            TextView textView_code = (TextView) view.findViewById(R.id.stop_code);
            getETA(position, textView_code.getText().toString());
        }
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        RouteStop object = mAdapter.getItem(position);
        if (null == object)
            return false;
        Intent intent = new Intent(mContext, RouteEtaDialog.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(Constants.BUNDLE.ITEM_POSITION, position);
        intent.putExtra(Constants.BUNDLE.STOP_OBJECT, object);
        startActivity(intent);
        return true;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        mMenu = menu;
        menu.findItem(R.id.action_settings).setVisible(false);
        menu.findItem(R.id.action_refresh).setVisible(savedState);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_refresh) {
            onRefresh();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
     public void onRefresh() {
        iEta = 0;
        if (null != mEtaHandler && null != mEtaRunnable)
            mEtaHandler.post(mEtaRunnable);
    }

    private void getRouteStops(final String route_no, final String route_bound) {

        if (mEmptyText != null)
            mEmptyText.setText(R.string.message_loading);
        if (mProgressBar != null)
            mProgressBar.setVisibility(View.VISIBLE);

        String _random_t = ((Double) Math.random()).toString();

        Uri routeStopUri = Uri.parse(getRouteInfoApi)
                .buildUpon()
                .appendQueryParameter("t", _random_t)
                .appendQueryParameter("chkroutebound", "true")
                .appendQueryParameter("field9", route_no)
                .appendQueryParameter("routebound", route_bound)
                .build();

        Ion.with(mContext)
                .load(routeStopUri.toString())
                //.setLogging("Ion", Log.DEBUG)
                .progressBar(mProgressBar)
                .setHeader("Referer", Constants.URL.REQUEST_REFERRER)
                .setHeader("X-Requested-With", "XMLHttpRequest")
                .setHeader("Pragma", "no-cache")
                .setHeader("User-Agent", Constants.URL.REQUEST_UA)
                .asJsonObject()
                .withResponse()
                .setCallback(new FutureCallback<Response<JsonObject>>() {
                    @Override
                    public void onCompleted(Exception e, Response<JsonObject> response) {
                        // do stuff with the result or error
                        if (e != null) {
                            Log.e(TAG, e.toString());
                            if (mEmptyText != null)
                                mEmptyText.setText(R.string.message_fail_to_request);
                        }
                        if (null != response && response.getHeaders().code() == 200) {
                            JsonObject result = response.getResult();
                            //Log.d(TAG, result.toString());
                            if (null != result)
                            if (result.get("valid").getAsBoolean() == true) {
                                //  Got Bus Line Stops
                                JsonArray _bus_arr = result.getAsJsonArray("bus_arr");
                                int seq = 0;
                                for (JsonElement element : _bus_arr) {
                                    Gson gson = new Gson();
                                    RouteStop routeStop = gson.fromJson(element.getAsJsonObject(), RouteStop.class);
                                    routeStop.route_bound = _routeBound;
                                    routeStop.stop_seq = String.valueOf(seq);
                                    Cursor cursor = mDatabase.getExist(routeStop);
                                    routeStop.favourite = (null != cursor && cursor.getCount() > 0);
                                    mAdapter.add(routeStop);
                                    seq++;
                                }
                                _id = result.get("id").getAsString();
                                _token = result.get("token").getAsString();
                                getRouteFares(route_no, route_bound, "01");
                                if (mEmptyText != null)
                                    mEmptyText.setText("");

                                if (null != mMenu)
                                    mMenu.findItem(R.id.action_refresh).setVisible(true);

                            } else if (result.get("valid").getAsBoolean() == false &&
                                    !result.get("message").getAsString().equals("")) {
                                // Invalid request with output message
                                if (mEmptyText != null)
                                    mEmptyText.setText(result.get("message").getAsString());
                            }
                        } else {
                            switchGetRouteInfoApi();
                            getRouteStops(route_no, route_bound);
                        }
                        if (mProgressBar != null)
                            mProgressBar.setVisibility(View.GONE);
                    }
                });

    }

    private void switchGetRouteInfoApi() {
        if (getRouteInfoApi.equals(Constants.URL.ROUTE_INFO)) {
            getRouteInfoApi = Constants.URL.ROUTE_INFO_V1;
        } else {
            getRouteInfoApi = Constants.URL.ROUTE_INFO;
        }
    }

    private void getRouteFares(final String route_no, final String route_bound, final String route_st) {

        if (mSwipeRefreshLayout != null)
            mSwipeRefreshLayout.setRefreshing(true);
        List<RouteStop> routeStopList = mAdapter.getAllItems();
        for (int j = 0; j < routeStopList.size(); j++) {
            RouteStop routeStop = routeStopList.get(j);
            routeStop.fare = mContext.getString(R.string.dots);
        }
        mAdapter.notifyDataSetChanged();

        Ion.with(mContext)
                .load(Constants.URL.ROUTE_MAP)
                .setHeader("Referer", Constants.URL.HTML_SEARCH)
                .setHeader("X-Requested-With", "XMLHttpRequest")
                .setHeader("Pragma", "no-cache")
                .setHeader("User-Agent", Constants.URL.REQUEST_UA)
                .setBodyParameter("bn", route_no)
                .setBodyParameter("dir", route_bound)
                .setBodyParameter("ST", route_st)
                .asJsonArray()
                .setCallback(new FutureCallback<JsonArray>() {
                    @Override
                    public void onCompleted(Exception e, JsonArray jsonArray) {
                        // do stuff with the result or error
                        if (e != null) {
                            Log.e(TAG, e.toString());
                        }
                        List<RouteStop> routeStopList = mAdapter.getAllItems();
                        if (null != jsonArray) {
                            for (int i = 0; i < jsonArray.size(); i++) {
                                JsonObject object = jsonArray.get(i).getAsJsonObject();
                                if (null != object) {
                                    Gson gson = new Gson();
                                    RouteStopMap routeStopMap = gson.fromJson(object, RouteStopMap.class);
                                    if (null != routeStopMap.subarea) {
                                        for (int j = 0; j < routeStopList.size(); j++) {
                                            RouteStop routeStop = routeStopList.get(j);
                                            String stopCode = routeStop.code;
                                            if (stopCode.equals(routeStopMap.subarea)) {
                                                if (null != routeStopMap.air_cond_fare &&
                                                        !routeStopMap.air_cond_fare.equals("") &&
                                                        !routeStopMap.air_cond_fare.equals("0.00"))
                                                routeStop.fare = mContext.getString(R.string.hkd, routeStopMap.air_cond_fare);
                                            }
                                        }
                                    }
                                }
                            }
                            for (int j = 0; j < routeStopList.size(); j++) {
                                RouteStop routeStop = routeStopList.get(j);
                                if (null != routeStop.fare &&
                                        routeStop.fare.equals(mContext.getString(R.string.dots)))
                                    routeStop.fare = "";
                            }
                            mAdapter.notifyDataSetChanged();
                            if (mSwipeRefreshLayout != null)
                                mSwipeRefreshLayout.setRefreshing(false);
                        }
                    }
                });

    }

    private void getETA(final int position, final String stop_code) {
        switch (settingsHelper.getEtaApi()) {
            case 1:
                getETAv1(position, stop_code);
                break;
            case 2:
            default:
                getETAv2(position, stop_code);
                break;
        }
    }

    private void getETAv2(final int position, final String stop_code) {
        RouteStop routeStop = mAdapter.getItem(position);
        routeStop.eta_loading = true;
        mAdapter.notifyDataSetChanged();
        final String stopCode = stop_code.replaceAll("-", "");
        final String stopSeq = String.valueOf(position);

        Uri routeEtaUri = Uri.parse(Constants.URL.ETA_MOBILE_API)
                .buildUpon()
                .appendQueryParameter("action", "geteta")
                .appendQueryParameter("lang", "tc")
                .appendQueryParameter("route", _route_no)
                .appendQueryParameter("bound", _route_bound)
                .appendQueryParameter("stop", stopCode)
                .appendQueryParameter("stop_seq", stopSeq)
                .build();

        Ion.with(mContext)
                .load(routeEtaUri.toString())
                .setHeader("X-Requested-With", "XMLHttpRequest")
                .asJsonObject()
                .setCallback(new FutureCallback<JsonObject>() {
                    @Override
                    public void onCompleted(Exception e, JsonObject result) {
                        // do stuff with the result or error
                        if (e != null) {
                            Log.e(TAG, e.toString());
                        }
                        RouteStop routeStop = mAdapter.getItem(position);
                        routeStop.eta_loading = true;
                        routeStop.eta_fail = false;
                        if (result != null) {
                            // Log.d(TAG, result.toString());
                            if (!result.has("response")) {
                                routeStop.eta_loading = false;
                                routeStop.eta_fail = true;
                                // routeStop.eta_fail = result.has("generated") ? false : true;
                                mAdapter.notifyDataSetChanged();
                                getETAv1(position, stopCode);
                                return;
                            }

                            JsonArray jsonArray = result.get("response").getAsJsonArray();
                            RouteStopETA routeStopETA = new RouteStopETA();
                            routeStopETA.api_version = 2;
                            routeStopETA.seq = stopSeq;
                            routeStopETA.updated = result.get("updated").getAsString();
                            routeStopETA.server_time = result.get("generated").getAsString();
                            StringBuilder etas = new StringBuilder();
                            StringBuilder expires = new StringBuilder();
                            for (int i = 0; i < jsonArray.size(); i++) {
                                JsonObject object = jsonArray.get(i).getAsJsonObject();
                                etas.append(object.get("t").getAsString());
                                expires.append(object.get("ex").getAsString());
                                if (i < jsonArray.size() - 1) {
                                    etas.append(", ");
                                    expires.append(", ");
                                }
                            }
                            routeStopETA.etas = etas.toString();
                            routeStopETA.expires = expires.toString();
                            routeStop.eta = routeStopETA;
                        }
                        routeStop.eta_loading = false;
                        mAdapter.notifyDataSetChanged();
                    }
                });
    }

    private void getETAv1(final int position, String bus_stop) {
        RouteStop routeStop = mAdapter.getItem(position);
        routeStop.eta_loading = true;
        mAdapter.notifyDataSetChanged();

        String stop_seq = String.valueOf(position);
        String _random_t = ((Double) Math.random()).toString();

        if (etaApi.equals("")) {
            findEtaApiUrl();
            routeStop.eta_loading = false;
            routeStop.eta_fail = true;
            mAdapter.notifyDataSetChanged();
        } else
        Ion.with(mContext)
                .load(etaApi + _random_t)
                //.setLogging("Ion", Log.DEBUG)
                .setHeader("Referer", Constants.URL.REQUEST_REFERRER)
                .setHeader("X-Requested-With", "XMLHttpRequest")
                .setHeader("Pragma", "no-cache")
                .setHeader("User-Agent", Constants.URL.REQUEST_UA)
                .setBodyParameter("route", _route_no)
                .setBodyParameter("route_no", _route_no)
                .setBodyParameter("bound", _route_bound)
                .setBodyParameter("busstop", bus_stop)
                .setBodyParameter("lang", "tc")
                .setBodyParameter("stopseq", stop_seq)
                .setBodyParameter("id", _id)
                .setBodyParameter("token", _token)
                .asString()
                .setCallback(new FutureCallback<String>() {
                    @Override
                    public void onCompleted(Exception e, String result) {
                        // do stuff with the result or error
                        if (e != null) {
                            Log.e(TAG, e.toString());
                        }
                        RouteStop routeStop = mAdapter.getItem(position);
                        routeStop.eta_loading = true;
                        routeStop.eta_fail = false;
                        if (result != null) {
                            //Log.d(TAG, result);
                            if (!result.contains("ETA_TIME")) {
                                findEtaApiUrl();
                                routeStop.eta_loading = false;
                                routeStop.eta_fail = true;
                                mAdapter.notifyDataSetChanged();
                                return;
                            }

                            RouteStopETA routeStopETA = new RouteStopETA();
                            // TODO: parse result [], ignore php error
                            JsonParser jsonParser = new JsonParser();
                            JsonArray jsonArray = jsonParser.parse(result).getAsJsonArray();
                            for (final JsonElement element : jsonArray) {
                                routeStopETA = new Gson().fromJson(element.getAsJsonObject(), RouteStopETA.class);
                                routeStopETA.api_version = 1;
                            }
                            routeStop.eta = routeStopETA;
                        }
                        routeStop.eta_loading = false;
                        mAdapter.notifyDataSetChanged();
                    }
                });
    }

    private void findEtaApiUrl() {
        // Find ETA API URL, by first finding the js file use to call eta api on web
        if (mSwipeRefreshLayout != null)
            mSwipeRefreshLayout.setRefreshing(true);
        Ion.with(mContext)
                .load(Constants.URL.HTML_ETA)
                .setHeader("Referer", Constants.URL.KMB)
                .setHeader("User-Agent", Constants.URL.REQUEST_UA)
                .asString()
                .setCallback(new FutureCallback<String>() {
                    @Override
                    public void onCompleted(Exception e, String result) {
                        // do stuff with the result or error
                        if (e != null) {
                            Log.e(TAG, e.toString());
                        }
                        if (result != null && !result.equals("")) {
                            Pattern p = Pattern.compile("\"(" + Constants.URL.PATH_ETA_JS + "[a-zA-Z0-9_.]*\\.js\\?[a-zA-Z0-9]*)\"");
                            Matcher m = p.matcher(result);
                            if (m.find()) {
                                String etaJs = Constants.URL.KMB + m.group(1);
                                findEtaApi(etaJs);
                                Log.d(TAG, "etaJs: " + etaJs);
                            }
                        }
                        if (mSwipeRefreshLayout != null)
                            mSwipeRefreshLayout.setRefreshing(false);
                    }
                });
    }

    private void findEtaApi(String JS_ETA) {
        // Find ETA API Url in found JS file
        if (mSwipeRefreshLayout != null)
            mSwipeRefreshLayout.setRefreshing(true);
        Ion.with(mContext)
                .load(JS_ETA)
                .setHeader("Referer", Constants.URL.REQUEST_REFERRER)
                .setHeader("User-Agent", Constants.URL.REQUEST_UA)
                .asString()
                .setCallback(new FutureCallback<String>() {
                    @Override
                    public void onCompleted(Exception e, String result) {
                        // do stuff with the result or error
                        if (e != null) {
                            Log.e(TAG, e.toString());
                        }
                        if (result != null && !result.equals("")) {
                            Pattern p = Pattern.compile("\"(" + Constants.URL.PATH_ETA_API + "[a-zA-Z0-9_.]*\\.php\\?[a-zA-Z0-9]*=)\"");
                            Matcher m = p.matcher(result);
                            if (m.find()) {
                                etaApi = Constants.URL.KMB + m.group(1);
                                Log.d(TAG, "etaApi: easy found " + etaApi);
                            } else {

                                Pattern p2 = Pattern.compile("\\|([^\\|]*)\\|\\|(t[a-zA-Z0-9_.]*)\\|prod");
                                Matcher m2 = p2.matcher(result);

                                if (m2.find() && m2.groupCount() == 2) {
                                    etaApi = Constants.URL.KMB + Constants.URL.PATH_ETA_API
                                            + m2.group(1) + ".php?" + m2.group(2);
                                    Log.d(TAG, "etaApi: found-nd " + etaApi);
                                } else {

                                    Pattern p3 = Pattern.compile("\\|([^\\|]*)\\|(t[a-zA-Z0-9_.]*)\\|eq");
                                    Matcher m3 = p3.matcher(result);

                                    if (m3.find() && m3.groupCount() == 2) {
                                        etaApi = Constants.URL.KMB + Constants.URL.PATH_ETA_API
                                                + m3.group(1) + ".php?" + m3.group(2);
                                        Log.d(TAG, "etaApi: found-rd " + etaApi);
                                    } else {
                                        Log.d(TAG, "etaApi: fail " + etaApi);
                                    }

                                }

                            }
                        }
                        if (mSwipeRefreshLayout != null)
                            mSwipeRefreshLayout.setRefreshing(false);
                    }
                });
    }

    public class UpdateViewReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle bundle = intent.getExtras();
            Boolean aBoolean = bundle.getBoolean(Constants.MESSAGE.STOP_UPDATED);
            if (null != mAdapter && aBoolean == true) {
                RouteStop newObject = bundle.getParcelable(Constants.BUNDLE.STOP_OBJECT);
                if (null != newObject) {
                    int position = Integer.parseInt(newObject.stop_seq);
                    if (position < mAdapter.getCount()) {
                        RouteStop oldObject = mAdapter.getItem(position);
                        oldObject.favourite = newObject.favourite;
                        oldObject.eta = newObject.eta;
                        mAdapter.notifyDataSetChanged();
                    }
                }
            }
        }
    }

}