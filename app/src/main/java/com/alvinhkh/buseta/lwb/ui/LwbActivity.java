package com.alvinhkh.buseta.lwb.ui;

import com.alvinhkh.buseta.C;
import com.alvinhkh.buseta.R;
import com.alvinhkh.buseta.lwb.LwbService;
import com.alvinhkh.buseta.lwb.model.LwbRouteBound;
import com.alvinhkh.buseta.lwb.model.network.LwbRouteBoundRes;
import com.alvinhkh.buseta.model.Route;
import com.alvinhkh.buseta.ui.route.RouteActivityAbstract;
import com.alvinhkh.buseta.utils.ConnectivityUtil;
import com.alvinhkh.buseta.utils.RetryWithDelay;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.observers.DisposableObserver;
import io.reactivex.schedulers.Schedulers;
import timber.log.Timber;

public class LwbActivity extends RouteActivityAbstract {

    private final LwbService lwbService = LwbService.retrofit.create(LwbService.class);

    @Override
    protected void loadRouteNo(String no) {
        super.loadRouteNo(no);
        getDisposables().add(lwbService.getRouteBound(no, Math.random())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeWith(routeBoundObserver()));
    }

    DisposableObserver<LwbRouteBoundRes> routeBoundObserver() {
        return new DisposableObserver<LwbRouteBoundRes>() {

            List<Route> routes = new ArrayList<>();

            @Override
            public void onNext(LwbRouteBoundRes res) {
                if (res != null && res.bus_arr != null) {
                    int i = 1;
                    for (LwbRouteBound bound : res.bus_arr) {
                        if (bound == null) continue;
                        Route route = new Route();
                        route.setCompanyCode(C.PROVIDER.KMB);
                        route.setOrigin(bound.origin_tc);
                        route.setDestination(bound.destination_tc);
                        route.setName(getRouteNo());
                        route.setSequence(String.valueOf(i++));
                        route.setServiceType("01");
                        routes.add(route);
                    }
                }
            }

            @Override
            public void onError(Throwable e) {
                Timber.d(e);
                runOnUiThread(() -> {
                    showEmptyView();
                    if (getEmptyText() != null) {
                        if (!ConnectivityUtil.isConnected(getApplicationContext())) {
                            getEmptyText().setText(R.string.message_no_internet_connection);
                        } else {
                            getEmptyText().setText(R.string.message_fail_to_request);
                        }
                    }
                });
            }

            @Override
            public void onComplete() {
                runOnUiThread(() -> onCompleteRoute(routes, C.PROVIDER.KMB));
            }
        };
    }
}
