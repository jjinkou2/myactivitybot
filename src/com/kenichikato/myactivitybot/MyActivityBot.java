package com.kenichikato.myactivitybot;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

public class MyActivityBot extends Activity implements SensorEventListener {
	
	// Debugging
	private static final String TAG = MyActivityBot.class.getSimpleName();
	private static final boolean D = false;		// During Development & Debugging

	//-----[ View ID VAR ]-----
	ImageView leftOffPix;
	ImageView leftOnPix;
	ImageView rightOffPix;
	ImageView rightOnPix;
	ImageView forwardOffPix;
	ImageView forwardOnPix;
	ImageButton offBtn;
	ImageButton onBtn;
	ImageButton bluetoothBtn;
	private TextView tv_xPos;
	private TextView tv_yPos;
	private TextView tv_zPos;

	//-----[ Sensors VAR ]-----
    //private SimulationView mSimulationView;
    private SensorManager mSensorManager;
    private Sensor mAccelerometer;
    //-----[ Bluetooth VAR ]-----
    private BluetoothAdapter mBluetoothAdapter;
    // Key names received from the BluetoothChatService Handler
    public static final String DEVICE_NAME = "device_name";
    public static final String TOAST = "toast";    
    // Message types sent from the MyActivityBotService Handler
    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;
    // Intent request codes
    /*    
    private static final int REQUEST_CONNECT_DEVICE_SECURE = 1;
    private static final int REQUEST_CONNECT_DEVICE_INSECURE = 2;
    private static final int REQUEST_ENABLE_BT = 3; */
    // Intent request codes
    private static final int REQUEST_CONNECT_DEVICE = 1;
    private static final int REQUEST_ENABLE_BT = 2;    
    // Comm Service
    private MyActivityBotService mChatService = null;
    // Name of the connected device
    private String mConnectedDeviceName = null;
    // Array adapter for the conversation thread
    private ArrayAdapter<String> mConversationArrayAdapter;
    // String buffer for outgoing messages
    private StringBuffer mOutStringBuffer;
	
	// Global Var
	private boolean powerSwitchStatus = false;
	private boolean userPwrSwtSelection = false;	// Default is OFF
	private byte checkSumByte = 0x7F;
	private byte[] startByte = new byte[] {(byte) (checkSumByte ^ 0xA1)};
	private byte[] stopByte = new byte[] {(byte) (checkSumByte ^ 0xAF)};
	private byte[] dataStream = new byte[4];		// Buffer for sending startChkByte, leftWheel, rightWheel, forwardMultiplier value
	private int weightedAvr = 10; 		// Averaging the values before sending
	private int weightedCnt = 0;
	private int leftWheel;
	private int rightWheel;
	private int forwardMultiplier;
	private float x;
	private float y;
	private float z=0;
	
    // State variables
    private boolean paused = false;
    private boolean connected = false;
	

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_my_activity_bot);

		// Setup Views
		setupView();
		
		// Setup Sensors
		setupSensors();
		
		// Setup Bluetooth & Comm Service
        setupBT();
		
	}


	private void setupBT() {	// Get BT Adaptor & Start BT
		// Get local Bluetooth adapter
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();		
        if (mBluetoothAdapter == null) {
            DisplayToastMsg("Bluetooth is not available");
            finish();
            return;
        }
        setupComm();
	}




	public void onClickBTBtn(View view){
		Intent serverIntent = new Intent(this, DeviceListActivity.class);
        startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);		
	}

	
    // The Handler that gets information back from the BluetoothService
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MESSAGE_STATE_CHANGE:
                if(D) Log.i(TAG, "MESSAGE_STATE_CHANGE: " + msg.arg1);
                switch (msg.arg1) {
                case MyActivityBotService.STATE_CONNECTED:
                	connected = true;
                	enableDisablePwrBtn(connected);		// Enable power button to switch ON 
                	DisplayToastMsg(mConnectedDeviceName);
                    break;
                case MyActivityBotService.STATE_CONNECTING:
                    //mTitle.setText(R.string.title_connecting);
                	DisplayToastMsg("Connecting, pls wait...");
                    break;
                case MyActivityBotService.STATE_NONE:
                	connected = false;
                	enableDisablePwrBtn(connected);		// Enable power button to switch OFF
                	DisplayToastMsg("(o_o) Not Connected");
                    break;
                }
                //onBluetoothStateChanged();
                break;
            case MESSAGE_WRITE:
                byte[] writeBuf = (byte[]) msg.obj;
                // construct a string from the buffer
                //String writeMessage = new String(writeBuf);
                //mConversationArrayAdapter.add(">>> " + writeMessage);
                //if (D) Log.d(TAG, "written = '" + writeMessage + "'");
                break;
            case MESSAGE_READ:
            	if (paused) break;
                byte[] readBuf = (byte[]) msg.obj;
                // construct a string from the valid bytes in the buffer
                String readMessage = new String(readBuf, 0, msg.arg1);
                if (D) Log.d(TAG, readMessage);
                //mConversationArrayAdapter.add(readMessage);
                break;
            case MESSAGE_DEVICE_NAME:
                // save the connected device's name
                mConnectedDeviceName = msg.getData().getString(DEVICE_NAME);
                Toast.makeText(getApplicationContext(), "Connected to "
                               + mConnectedDeviceName, Toast.LENGTH_SHORT).show();
                break;
            case MESSAGE_TOAST:
                Toast.makeText(getApplicationContext(), msg.getData().getString(TOAST),
                               Toast.LENGTH_SHORT).show();
                break;
            }
        }
    };	
	
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(D) Log.d(TAG, "onActivityResult " + resultCode);
        switch (requestCode) {
        case REQUEST_CONNECT_DEVICE:
            // When DeviceListActivity returns with a device to connect
        	if(D) Log.d(TAG, "!! In REQUEST CONNECT DEVICE !!");	//<<-- Passed before CRASHEd
            if (resultCode == Activity.RESULT_OK) {
                // Get the device MAC address
                String address = data.getExtras().getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
                if(D) Log.d(TAG, "After String address..." + address);
                // Get the BLuetoothDevice object
                BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
                if(D) Log.d(TAG, "After BluetoothDevice device..." + device);
                // Attempt to connect to the device
                mChatService.connect(device);
            }
            break;
        case REQUEST_ENABLE_BT:
            // When the request to enable Bluetooth returns
            if (resultCode == Activity.RESULT_OK) {
                // Bluetooth is now enabled, so set up a Bluetooth session
                //setupUserInterface();
            } else {
                // User did not enable Bluetooth or an error occurred
                Log.d(TAG, "BT not enabled");
                Toast.makeText(this, R.string.bt_not_enabled_leaving, Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

	


	@Override
	protected void onStart() {
		super.onStart();
		// If BT is not on, request that it be enabled.
        // setupChat() will then be called during onActivityResult
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
        // Otherwise, setup the chat session
        } else {
            //if (mChatService == null) setupComm();		//<-- Basically to setup UI before Connection
        	
        }				
	}



	private void enableDisablePwrBtn(boolean flag){
		if(flag){
			// Connected, enable Power Switch
			offBtn.setClickable(true);
		}else{
			// Disable
			offBtn.setClickable(false);
		}
	}




	private void setupComm() {
		// Initialize the BluetoothService to perform Bluetooth connections
		mChatService = new MyActivityBotService(mHandler);
		
	}

	
	
	private void startDeviceListActivity() {
        Intent serverIntent = new Intent(this, DeviceListActivity.class);
        startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
    }

	


	@Override
	protected void onDestroy() {
		super.onDestroy();
		//if (mChatService != null) mChatService.stop();	// Placed in onPause()
        if(D) Log.e(TAG, "--- ON DESTROY ---");		
	}


	@Override
	protected void onPause() {
		super.onPause();
		if(D) Log.d(TAG, "onPause...");
		// Unregister Listener
		mSensorManager.unregisterListener(this);
		if (mChatService != null) mChatService.stop();
	}



	@Override
	protected void onResume() {
		super.onResume();
		if(D) Log.d(TAG, "onResume...");
		
		//---[ Sensor Register Listener ]---
		mSensorManager.registerListener(new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                x = event.values[0];
                y = event.values[1];

                // Updating Views & Cmd to ActivityBot
                if(connected){
                    if(powerSwitchStatus){
                    	if(!userPwrSwtSelection){
                    		// Sends START byte
                    		mChatService.write(startByte);
                    		userPwrSwtSelection = true;
                    	}
                    	
                    	// Update View
                    	updateStatus(x, y, z);
                    	
                    	// Format to remove noise 
                    	if(x>3){	// Left-Turn
                    		leftWheel = 4;
                    		rightWheel = 8;
                    	}else if(x<-3){		// Right-Turn
                    		leftWheel = 8;
                            rightWheel = 4;
                    	}else{
                    		leftWheel = 8;
                    		rightWheel = 8;
                    	}
                    	
                    	if(y<-8){	// Stop
                    		forwardMultiplier = 0;
                    	}else if(y<-4){
                    		forwardMultiplier = 2;
                    	}else if(y<0){
                    		forwardMultiplier = 3;
                    	}else if(y<1){
                    		forwardMultiplier = 4;
                    	}else if(y<2){
                    		forwardMultiplier = 5;
                    	}else if(y<3){
                    		forwardMultiplier = 6;
                    	}else if(y<4){
                    		forwardMultiplier = 7;
                    	}else if(y<5){
                    		forwardMultiplier = 8;
                    	}else if(y<6){
                    		forwardMultiplier = 9;
                    	}else if(y<7){
                    		forwardMultiplier = 10;
                    	}else{
                    		forwardMultiplier = 11;
                    	}
                    	
                    	dataStream[0] = checkSumByte;
                    	dataStream[1] = (byte) (leftWheel & 0xFF);
                    	dataStream[2] = (byte) (rightWheel & 0xFF);
                    	dataStream[3] = (byte) (forwardMultiplier & 0xFF);
                    	mChatService.write(dataStream);
                    	if(D) Log.d(TAG, "Left: " + leftWheel + " | Right: " + rightWheel + " | FMul: " + forwardMultiplier);
                    	
                    }else{
                    	allSignalsOff();
                    	if(userPwrSwtSelection){
                    		// Sends STOP byte
                    		mChatService.write(stopByte);
                    		userPwrSwtSelection = false;
                    	}
                    	
                    	
                    }
                }
            }
            private void allSignalsOff() {
            	// Disable 
            	forwardOnPix.setVisibility(View.INVISIBLE);
            	forwardOffPix.setVisibility(View.VISIBLE);
            	leftOnPix.setVisibility(View.INVISIBLE);
            	leftOffPix.setVisibility(View.VISIBLE);
            	rightOnPix.setVisibility(View.INVISIBLE);
            	rightOffPix.setVisibility(View.VISIBLE);
			}
			private void updateStatus(float x, float y, float z) {
            	// Debug Info
            	tv_xPos.setText(Float.toString(x));
                tv_yPos.setText(Float.toString(y));
                tv_zPos.setText(Float.toString(z));
                
                // Forward
                if(y>-8){
                	forwardOnPix.setVisibility(View.VISIBLE);
                	forwardOffPix.setVisibility(View.INVISIBLE);
                }else{
                	forwardOnPix.setVisibility(View.INVISIBLE);
                	forwardOffPix.setVisibility(View.VISIBLE);
                }
                if(x>3){
                	// Left turn
                	leftOnPix.setVisibility(View.VISIBLE);
                	leftOffPix.setVisibility(View.INVISIBLE);
                	rightOffPix.setVisibility(View.VISIBLE);
                	rightOnPix.setVisibility(View.INVISIBLE);
                }else if(x<-3){
                	// Right Turn
                	leftOnPix.setVisibility(View.INVISIBLE);
                	leftOffPix.setVisibility(View.VISIBLE);
                	rightOffPix.setVisibility(View.INVISIBLE);
                	rightOnPix.setVisibility(View.VISIBLE);
                	}else{
                		// Forward
                    	leftOnPix.setVisibility(View.INVISIBLE);
                    	leftOffPix.setVisibility(View.VISIBLE);
                    	rightOffPix.setVisibility(View.VISIBLE);
                    	rightOnPix.setVisibility(View.INVISIBLE);
                	}
			}
			@Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        }, mAccelerometer, SensorManager.SENSOR_DELAY_FASTEST);		
	}


	private void setupSensors() {
		// Get an instance of the SensorManager
		if(D) Log.d(TAG, "setupSensors()...");
		
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
	}


	private void setupView() {
		if(D) Log.d (TAG, "Inside setupView()...");
		
		leftOnPix = (ImageView) findViewById(R.id.leftOnPix);
		leftOffPix = (ImageView) findViewById(R.id.leftOffPix);
		rightOnPix = (ImageView) findViewById(R.id.rightOnPix);
		rightOffPix = (ImageView) findViewById(R.id.rightOffPix);
		forwardOnPix = (ImageView) findViewById(R.id.forwardOnPix);
		forwardOffPix = (ImageView) findViewById(R.id.forwardOffPix);
		tv_xPos = (TextView) findViewById(R.id.tv_xPos);
		tv_yPos = (TextView) findViewById(R.id.tv_yPos);
		tv_zPos = (TextView) findViewById(R.id.tv_zPos);
		offBtn = (ImageButton) findViewById(R.id.offBtn);
		onBtn = (ImageButton) findViewById(R.id.onBtn);
		bluetoothBtn = (ImageButton) findViewById(R.id.bluetoothBtn);
		offBtn.setClickable(false);		//Default until user press Bluetooth
		
	}

	public void onClickOffBtn(View view){
		// User Powering ON
		offBtn.setVisibility(View.INVISIBLE);
		offBtn.setClickable(false);
		onBtn.setVisibility(View.VISIBLE);
		onBtn.setClickable(true);
		powerSwitchStatus = true;
	}
	
	public void onClickOnBtn(View view){
		// User Powering OFF
		onBtn.setVisibility(View.INVISIBLE);
		onBtn.setClickable(false);
		offBtn.setVisibility(View.VISIBLE);
		offBtn.setClickable(true);
		powerSwitchStatus = false;
	}


	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.my_activity_bot, menu);
		return true;
	}


	@Override
	public void onAccuracyChanged(Sensor arg0, int arg1) {
		// TODO Auto-generated method stub
		
	}


	@Override
	public void onSensorChanged(SensorEvent event) {
		// TODO Auto-generated method stub
		
	}
	
	
	private void DisplayToastMsg(String msg){
		Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
	}

}
