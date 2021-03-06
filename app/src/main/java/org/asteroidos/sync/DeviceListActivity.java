/*
 * Copyright (C) 2016 - Florent Revest <revestflo@gmail.com>
 *                      Doug Koellmer <dougkoellmer@hotmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.asteroidos.sync;

import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.design.widget.FloatingActionButton;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;


import com.idevicesinc.sweetblue.BleDevice;
import com.idevicesinc.sweetblue.BleManager;
import com.idevicesinc.sweetblue.BleManagerConfig;
import com.idevicesinc.sweetblue.BleManagerState;
import com.idevicesinc.sweetblue.utils.BluetoothEnabler;
import com.idevicesinc.sweetblue.utils.Interval;
import com.skyfishjy.library.RippleBackground;

import org.asteroidos.sync.services.NLService;

import java.util.ArrayList;

import static com.idevicesinc.sweetblue.BleManager.get;

public class DeviceListActivity extends AppCompatActivity implements BleManager.StateListener,
        BleManager.NativeStateListener, BleManager.DiscoveryListener, BleManager.UhOhListener {
    private static final Interval SCAN_TIMEOUT = Interval.secs(10.0);

    private BleManager mBleMngr;
    private final BleManagerConfig m_bleManagerConfig = new BleManagerConfig() {{
        this.undiscoveryKeepAlive = Interval.DISABLED;
        this.loggingEnabled = true;
    }};

    private LeDeviceListAdapter mLeDeviceListAdapter;

    private RippleBackground mRippleBackground;
    private TextView searchingText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_list);

        searchingText = (TextView)findViewById(R.id.searchingText);

        mRippleBackground = (RippleBackground)findViewById(R.id.content);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        assert fab != null;
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mBleMngr.turnOn();
                mBleMngr.startScan(SCAN_TIMEOUT);
                mRippleBackground.startRippleAnimation();
            }
        });

        BluetoothEnabler.start(this);
        mBleMngr = get(getApplication(), m_bleManagerConfig);
        mBleMngr.setListener_State(this);
        mBleMngr.setListener_NativeState(this);
        mBleMngr.setListener_Discovery(this);
        mBleMngr.setListener_UhOh(this);

        if (!mBleMngr.isBleSupported()) showBleNotSupported();
        else if (!mBleMngr.is(BleManagerState.ON))
            mBleMngr.turnOn();

        mBleMngr.startScan(SCAN_TIMEOUT);

        ListView mScanListView = (ListView) findViewById(R.id.device_list);
        assert mScanListView != null;
        mLeDeviceListAdapter = new LeDeviceListAdapter();
        mScanListView.setAdapter(mLeDeviceListAdapter);

        mScanListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                final BleDevice device = mLeDeviceListAdapter.getDevice(i);
                if (device == null) return;

                Context context = view.getContext();
                Intent intent = new Intent(context, DeviceDetailActivity.class);
                intent.putExtra(DeviceDetailActivity.ARG_DEVICE_ADDRESS, device.getMacAddress());

                context.startActivity(intent);
            }
        });

        ComponentName cn = new ComponentName(this, NLService.class);
        String flat = Settings.Secure.getString(this.getContentResolver(), "enabled_notification_listeners");
        if (!(flat != null && flat.contains(cn.flattenToString()))) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.notifications)
                    .setMessage(R.string.notifications_enablement)
                    .setPositiveButton(R.string.generic_ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            Intent intent=new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
                            startActivity(intent);
                        }
                    })
                    .show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mBleMngr.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mBleMngr.onPause();
    }

    @Override
    public void onEvent(BleManager.StateListener.StateEvent event) {
        if(event.didExit(BleManagerState.SCANNING)) {
            mRippleBackground.stopRippleAnimation();
            int deviceCount = mLeDeviceListAdapter.getCount();
            if(deviceCount == 0)
                searchingText.setText(R.string.nothing_found);
            else if(deviceCount == 1)
                searchingText.setText(R.string.one_found);
            else
                searchingText.setText(getString(R.string.n_found, deviceCount));
        }
        else if(event.didEnter(BleManagerState.SCANNING)) {
            mRippleBackground.startRippleAnimation();
            searchingText.setText(R.string.searching);
        }
    }

    @Override
    public void onEvent(BleManager.NativeStateListener.NativeStateEvent event) {}

    @Override
    public void onEvent(BleManager.DiscoveryListener.DiscoveryEvent event) {
        if (event.was(BleManager.DiscoveryListener.LifeCycle.DISCOVERED)) {
            mLeDeviceListAdapter.addDevice(event.device());
            mLeDeviceListAdapter.notifyDataSetChanged();
        } else if (event.was(BleManager.DiscoveryListener.LifeCycle.UNDISCOVERED)) {
            mLeDeviceListAdapter.removeDevice(event.device());
            mLeDeviceListAdapter.notifyDataSetChanged();
        }
    }

    // Adapter for holding devices found through scanning.
    private class LeDeviceListAdapter extends BaseAdapter {
        private ArrayList<BleDevice> mLeDevices;
        private LayoutInflater mInflator;

        public LeDeviceListAdapter() {
            super();
            mLeDevices = new ArrayList<>();
            mInflator = DeviceListActivity.this.getLayoutInflater();
        }

        public void addDevice(BleDevice device) {
            if (!mLeDevices.contains(device)) {
                mLeDevices.add(device);
            }
        }

        public void removeDevice(BleDevice device) {
            if (!mLeDevices.contains(device)) {
                mLeDevices.remove(device);
            }
        }

        public BleDevice getDevice(int position) {
            return mLeDevices.get(position);
        }

        public void clear() {
            mLeDevices.clear();
        }

        @Override
        public int getCount() {
            return mLeDevices.size();
        }

        @Override
        public Object getItem(int i) {
            return mLeDevices.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View view, ViewGroup viewGroup) {
            ViewHolder viewHolder;
            if (view == null) {
                view = mInflator.inflate(R.layout.device_list_item, null);
                viewHolder = new ViewHolder();
                viewHolder.deviceName = (TextView) view.findViewById(R.id.content);
                view.setTag(viewHolder);
            } else {
                viewHolder = (ViewHolder) view.getTag();
            }

            BleDevice device = mLeDevices.get(i);
            final String deviceName = device.getName_normalized();
            if (deviceName != null && deviceName.length() > 0)
                viewHolder.deviceName.setText(deviceName);
            else
                viewHolder.deviceName.setText(R.string.unknown_device);

            return view;
        }
    }

    @Override public void onEvent(UhOhEvent event)
    {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        final android.app.AlertDialog dialog = builder.create();

        DialogInterface.OnClickListener clickListener = new DialogInterface.OnClickListener()
        {
            @Override public void onClick(DialogInterface dialog, int which)
            {
                dialog.dismiss();

                if( which == DialogInterface.BUTTON_POSITIVE )
                    mBleMngr.reset();
            }
        };

        dialog.setTitle(event.uhOh().name());

        if( event.uhOh().getRemedy() == Remedy.RESET_BLE )
        {
            dialog.setMessage(getString(R.string.uhoh_message_nuke));
            dialog.setButton(DialogInterface.BUTTON_POSITIVE,
                    getString(R.string.uhoh_message_nuke_drop), clickListener);
            dialog.setButton(DialogInterface.BUTTON_NEGATIVE,
                    getString(R.string.generic_cancel), clickListener);
        }
        else if( event.uhOh().getRemedy() == Remedy.RESTART_PHONE )
        {
            dialog.setMessage(getString(R.string.uhoh_message_phone_restart));
            dialog.setButton(DialogInterface.BUTTON_NEUTRAL,
                    getString(R.string.generic_ok), clickListener);
        }
        else if( event.uhOh().getRemedy() == Remedy.WAIT_AND_SEE )
        {
            dialog.setMessage(getString(R.string.uhoh_message_weirdness));
            dialog.setButton(DialogInterface.BUTTON_NEUTRAL,
                    getString(R.string.generic_ok), clickListener);
        }

        dialog.show();
    }

    static class ViewHolder {
        TextView deviceName;
    }

    public void showBleNotSupported()
    {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        final android.app.AlertDialog dialog = builder.create();
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        DialogInterface.OnClickListener clickListener = new DialogInterface.OnClickListener()
        {
            @Override
            public void onClick(DialogInterface dialog, int which)
            {
                dialog.dismiss();
            }
        };

        dialog.setMessage(getString(R.string.ble_not_supported));
        dialog.setButton(DialogInterface.BUTTON_NEUTRAL,
                getString(R.string.generic_ok), clickListener);
        dialog.show();
    }
}
