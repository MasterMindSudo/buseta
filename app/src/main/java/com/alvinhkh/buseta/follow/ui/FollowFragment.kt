package com.alvinhkh.buseta.follow.ui

import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import android.os.Bundle
import android.os.Handler
import com.google.android.material.snackbar.Snackbar
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.*
import com.alvinhkh.buseta.C
import com.alvinhkh.buseta.R
import com.alvinhkh.buseta.arrivaltime.dao.ArrivalTimeDatabase
import com.alvinhkh.buseta.follow.dao.FollowDatabase
import com.alvinhkh.buseta.follow.model.Follow
import com.alvinhkh.buseta.follow.model.FollowGroup
import com.alvinhkh.buseta.utils.ConnectivityUtil
import java.lang.ref.WeakReference


class FollowFragment: Fragment() {

    private lateinit var arrivalTimeDatabase: ArrivalTimeDatabase
    private lateinit var followDatabase: FollowDatabase
    private lateinit var recyclerView: RecyclerView
    private var viewAdapter: FollowViewAdapter? = null
    private lateinit var emptyView: View
    private lateinit var snackbar: Snackbar
    private var groupId = FollowGroup.UNCATEGORISED

    private val refreshHandler = Handler()
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (ConnectivityUtil.isConnected(context)) {
                snackbar.dismiss()
                viewAdapter?.notifyDataSetChanged()
                refreshHandler.postDelayed(this, 30000)
            } else {
                snackbar.show()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val rootView = inflater.inflate(R.layout.fragment_follow, container, false)

        groupId = arguments?.getString(C.EXTRA.GROUP_ID)?:FollowGroup.UNCATEGORISED
        arrivalTimeDatabase = ArrivalTimeDatabase.getInstance(context!!)!!
        followDatabase = FollowDatabase.getInstance(context!!)!!

        snackbar = Snackbar.make(rootView?.findViewById(R.id.coordinator_layout)?:rootView, R.string.message_no_internet_connection, Snackbar.LENGTH_INDEFINITE)
        emptyView = rootView.findViewById(R.id.empty_view)
        emptyView.visibility = View.GONE
        recyclerView = rootView.findViewById(R.id.recycler_view)
        viewAdapter = FollowViewAdapter(WeakReference(activity!!))
        with(recyclerView) {
            setHasFixedSize(true)
            layoutManager = LinearLayoutManager(context)
            adapter = viewAdapter
        }
        val viewModel = ViewModelProviders.of(this).get(FollowViewModel::class.java)
        val liveData = viewModel.getAsLiveData(groupId)
        liveData.removeObservers(this)
        liveData.observe(this, Observer<MutableList<Follow>> { list ->
            viewAdapter?.replaceItems(list?: mutableListOf())
            list?.forEachIndexed { index, follow ->
                val id = follow.companyCode + follow.routeNo + follow.routeSeq + follow.routeServiceType + follow.stopId + follow.stopSeq
                val arrivalTimeLiveData = arrivalTimeDatabase.arrivalTimeDao().getLiveData(follow.companyCode, follow.routeNo, follow.routeSeq, follow.stopId, follow.stopSeq)
                arrivalTimeLiveData.removeObservers(this)
                arrivalTimeLiveData.observe(this, Observer { etas ->
                    if (etas != null && id == (follow.companyCode + follow.routeNo + follow.routeSeq + follow.routeServiceType + follow.stopId + follow.stopSeq)) {
                        follow.etas = listOf()
                        etas.forEach { eta ->
                            if (eta.updatedAt > System.currentTimeMillis() - 600000) {
                                follow.etas += eta
                            }
                        }
                        viewAdapter?.replaceItem(index, follow)
                    }
                })
            }
            emptyView.visibility = if (list?.size?:0 > 0) View.GONE else View.VISIBLE
        })
        return rootView
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.postDelayed(refreshRunnable, 500)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacksAndMessages(null)
    }

    companion object {

        fun newInstance(groupId: String): FollowFragment {
            val fragment = FollowFragment()
            val args = Bundle()
            args.putString(C.EXTRA.GROUP_ID, groupId)
            fragment.arguments = args
            return fragment
        }
    }
}
