package com.tradeanalyst.app;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.gson.Gson; // Added to handle serialization
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class MainActivity extends AppCompatActivity implements VoiceAssistantBottomSheet.AssistantListener, VoiceAssistantService.ServiceListener {

    private AppDatabase mDb;
    private TradingPreferences mPrefs;
    private ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    // Views
    private CandlestickChartView mChartView;
    private TextView mChartPriceText;
    private CheckBox mEmaCheck, mSmaCheck, mBbCheck, mSrCheck, mHideAllCheck;
    private TextView mRsiText, mTrendText, mAlertsCountText;
    private EditText mAlertPriceInput;
    private Button mCreateAlertBtn;
    
    private Button mTabNewsBtn, mTabTradesBtn;
    private SwipeRefreshLayout mSwipeRefresh;
    private RecyclerView mFeedRecycler;
    private FeedAdapter mFeedAdapter;
    private FloatingActionButton mVoiceAssistantFab;

    // Service bindings for background persistence
    private VoiceAssistantService mService;
    private boolean mBound = false;

    // Timeframe Interval Selectors
    private Button mBtn1m, mBtn5m, mBtn15m, mBtn1h, mBtn1D;
    private String mCurrentInterval = "1h";

    // State Variables
    private List<Candlestick> mCandles = new ArrayList<>();
    private double mCurrentPrice = 64812.50;
    private String mCurrentSymbol = "BTC/USDT";
    private int mActiveTab = 0; // 0 for News, 1 for Trades
    private final Random mRandom = new Random();
    private boolean mIsSimulationMode = false;
    private boolean mIsLoadingHistory = false;
    
    // Live session connection state tracker [1]
    private boolean mIsLiveSessionActive = false;

    // Simulated market news
    private List<FeedItem> mNewsList = new ArrayList<>();

    // Active bottom sheet reference
    private VoiceAssistantBottomSheet mActiveBottomSheet;

    // Cached pattern response candidate
    private ChartPatternResponse mLatestPatternCandidate = null;

    // Connection wrapper to bind persistent Foreground Service lifecycle
    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            VoiceAssistantService.LocalBinder binder = (VoiceAssistantService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
            mService.setListener(MainActivity.this); // Registers active callback handler
            Log.d("MainActivity", "Successfully bound to VoiceAssistantService.");
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBound = false;
            mService = null;
            Log.w("MainActivity", "VoiceAssistantService bound connection was severed.");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        mPrefs = new TradingPreferences(this);
        
        // Dynamically configure theme on start based on user preferences
        if (mPrefs.isDarkThemeEnabled()) {
            setTheme(R.style.Theme_MyApplication);
        } else {
            setTheme(R.style.Theme_MyApplication); // Standard DayNight allows fallback
        }

        setContentView(R.layout.activity_main);

        mDb = AppDatabase.getDatabase(this);

        // Bind Views
        initViews();

        // Load Live Candlestick data using public Binance APIs instead of local simulator
        loadBinanceCandles("1h");

        // Apply theme styles to Custom Chart
        applyThemeStyles(mPrefs.isDarkThemeEnabled());

        // Setup Listener Events
        setupListeners();

        // Bind Background Foreground Service instead of creating WebViews inside Activity thread
        startAndBindVoiceService();

        // Generate news updates
        generateSimulatedNews();

        // Default Load list
        refreshFeedList();

        // Start Price Ticker Simulator (live-updates price, candlestick updates and checks Alerts!)
        startLivePriceSimulator();
    }

    private void initViews() {
        mChartView = findViewById(R.id.candlestick_chart);
        mChartPriceText = findViewById(R.id.chart_price);
        
        mEmaCheck = findViewById(R.id.checkbox_ema20);
        mSmaCheck = findViewById(R.id.checkbox_sma20);
        mBbCheck = findViewById(R.id.checkbox_bb);
        mSrCheck = findViewById(R.id.checkbox_sr);
        mHideAllCheck = findViewById(R.id.checkbox_hide_all);

        mRsiText = findViewById(R.id.stat_rsi);
        mTrendText = findViewById(R.id.stat_trend);
        mAlertsCountText = findViewById(R.id.stat_alerts);

        mAlertPriceInput = findViewById(R.id.input_alert_price);
        mCreateAlertBtn = findViewById(R.id.btn_create_alert);

        mTabNewsBtn = findViewById(R.id.btn_tab_news);
        mTabTradesBtn = findViewById(R.id.btn_tab_trades);
        mSwipeRefresh = findViewById(R.id.swipe_refresh);
        mFeedRecycler = findViewById(R.id.feed_recycler_view);

        mFeedRecycler.setLayoutManager(new LinearLayoutManager(this));
        mFeedAdapter = new FeedAdapter();
        mFeedRecycler.setAdapter(mFeedAdapter);

        // Bind Timeframe Buttons
        mBtn1m = findViewById(R.id.btn_interval_1m);
        mBtn5m = findViewById(R.id.btn_interval_5m);
        mBtn15m = findViewById(R.id.btn_interval_15m);
        mBtn1h = findViewById(R.id.btn_interval_1h);
        mBtn1D = findViewById(R.id.btn_interval_1d);

        mVoiceAssistantFab = findViewById(R.id.btn_voice_assistant);
    }

    private void startAndBindVoiceService() {
        Intent intent = new Intent(this, VoiceAssistantService.class);
        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    private void applyThemeStyles(boolean isDark) {
        mChartView.setTheme(isDark);
        View rootLayout = findViewById(R.id.main_coordinator);
        View toolbar = findViewById(R.id.toolbar);
        TextView title = findViewById(R.id.toolbar_title);

        if (isDark) {
            rootLayout.setBackgroundColor(getResources().getColor(R.color.bg_dark_emerald, null));
            toolbar.setBackgroundColor(getResources().getColor(R.color.surface_dark_emerald, null));
            title.setTextColor(getResources().getColor(R.color.text_dark_theme, null));
        } else {
            rootLayout.setBackgroundColor(getResources().getColor(R.color.bg_light_sage, null));
            toolbar.setBackgroundColor(getResources().getColor(R.color.surface_light_sage, null));
            title.setTextColor(getResources().getColor(R.color.text_light_theme, null));
        }
    }

    /**
     * Connects asynchronously to the public Binance API endpoint to pull real kline charts
     */
    public void loadBinanceCandles(String interval) {
        mCurrentInterval = interval;
        mSwipeRefresh.setRefreshing(true);
        mIsSimulationMode = false;
        
        String normalizedSymbol = mCurrentSymbol.replace("/", "").toUpperCase();
        if (normalizedSymbol.endsWith("USD")) {
            normalizedSymbol = normalizedSymbol.substring(0, normalizedSymbol.length() - 3) + "USDT";
        }

        String binanceInterval = interval;
        if ("1D".equalsIgnoreCase(interval)) {
            binanceInterval = "1d";
        }

        OkHttpClient client = new OkHttpClient.Builder().build();
        Retrofit retrofit = new Retrofit.Builder()
            .baseUrl("https://api.binance.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build();

        BinanceApiService binanceService = retrofit.create(BinanceApiService.class);
        binanceService.getKlines(normalizedSymbol, binanceInterval, 1000).enqueue(new Callback<List<List<Object>>>() {
            @Override
            public void onResponse(Call<List<List<Object>>> call, Response<List<List<Object>>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    mCandles.clear();
                    for (List<Object> kline : response.body()) {
                        try {
                            long openTime = ((Number) kline.get(0)).longValue();
                            double open = Double.parseDouble(kline.get(1).toString());
                            double high = Double.parseDouble(kline.get(2).toString());
                            double low = Double.parseDouble(kline.get(3).toString());
                            double close = Double.parseDouble(kline.get(4).toString());
                            double volume = Double.parseDouble(kline.get(5).toString()); // Parse native volume

                            mCandles.add(new Candlestick(open, high, low, close, volume, openTime));
                        } catch (Exception e) {
                            Log.e("MainActivity", "Error mapping Binance kline arrays: " + e.getMessage());
                        }
                    }

                    if (!mCandles.isEmpty()) {
                        mCurrentPrice = mCandles.get(mCandles.size() - 1).close;
                        mChartPriceText.setText(String.format("$%,.2f", mCurrentPrice));
                        mChartView.setCandles(mCandles);

                        updateTechnicalStats();
                        runLocalPatternAnalysis(); // Execute mathematical analysis immediately on fresh klines
                    }
                } else {
                    Log.e("MainActivity", "Binance API call returned status failure, invoking fallback simulation");
                    generateInitialCandles(interval);
                }
                mSwipeRefresh.setRefreshing(false);
            }

            @Override
            public void onFailure(Call<List<List<Object>>> call, Throwable t) {
                Log.e("MainActivity", "Binance API connection dropped, invoking fallback simulation", t);
                generateInitialCandles(interval);
                mSwipeRefresh.setRefreshing(false);
            }
        });
    }

    private void updateTechnicalStats() {
        if (mCandles.isEmpty()) return;
        
        double[] rsi = IndicatorsEngine.calculateRSI(mCandles, 14);
        double latestRsi = rsi[rsi.length - 1];
        mRsiText.setText(String.format("%.1f (%s)", latestRsi, latestRsi > 70 ? "Overbought" : (latestRsi < 30 ? "Oversold" : "Neutral")));

        double[] ema20 = IndicatorsEngine.calculateEMA(mCandles, 20);
        double latestEma = ema20[ema20.length - 1];
        if (mCurrentPrice > latestEma) {
            mTrendText.setText("Bullish EMA");
            mTrendText.setTextColor(Color.parseColor("#10B981"));
        } else {
            mTrendText.setText("Bearish EMA");
            mTrendText.setTextColor(Color.parseColor("#EF4444"));
        }
    }

    private void generateInitialCandles(String interval) {
        mIsSimulationMode = true;
        mCandles.clear();
        
        double startPrice = 64200.0;
        long timeStep = 3600 * 1000L; // default 1h
        double changeMult = 600.0;
        double wickMult = 200.0;
        
        if ("1m".equals(interval)) {
            timeStep = 60 * 1000L;
            changeMult = 15.0;
            wickMult = 5.0;
        } else if ("5m".equals(interval)) {
            timeStep = 5 * 60 * 1000L;
            changeMult = 50.0;
            wickMult = 15.0;
        } else if ("15m".equals(interval)) {
            timeStep = 15 * 60 * 1000L;
            changeMult = 120.0;
            wickMult = 40.0;
        } else if ("1h".equals(interval)) {
            timeStep = 3600 * 1000L;
            changeMult = 600.0;
            wickMult = 200.0;
        } else if ("1D".equals(interval)) {
            timeStep = 24 * 3600 * 1000L;
            changeMult = 2500.0;
            wickMult = 800.0;
        }
        
        long time = System.currentTimeMillis() - (1000 * timeStep);

        for (int i = 0; i < 1000; i++) {
            double change = (mRandom.nextDouble() - 0.48) * changeMult; // slightly upward bias
            double open = startPrice;
            double close = startPrice + change;
            double high = Math.max(open, close) + mRandom.nextDouble() * wickMult;
            double low = Math.min(open, close) - mRandom.nextDouble() * wickMult;
            double volume = mRandom.nextDouble() * 500.0 + 100.0; // Simulated volume context

            mCandles.add(new Candlestick(open, high, low, close, volume, time));
            startPrice = close;
            time += timeStep;
        }

        mCurrentPrice = startPrice;
        mChartPriceText.setText(String.format("$%,.2f", mCurrentPrice));
        mChartView.setCandles(mCandles);

        updateTechnicalStats();
        runLocalPatternAnalysis(); // Scans generated dataset
    }

    public void loadMoreHistory() {
        if (mIsLoadingHistory) return;
        if (mCandles.isEmpty()) return;

        mIsLoadingHistory = true;
        mSwipeRefresh.setRefreshing(true);

        long oldestTime = mCandles.get(0).timestamp;
        String normalizedSymbol = mCurrentSymbol.replace("/", "").toUpperCase();
        if (normalizedSymbol.endsWith("USD")) {
            normalizedSymbol = normalizedSymbol.substring(0, normalizedSymbol.length() - 3) + "USDT";
        }

        String binanceInterval = mCurrentInterval;
        if ("1D".equalsIgnoreCase(mCurrentInterval)) {
            binanceInterval = "1d";
        }

        OkHttpClient client = new OkHttpClient.Builder().build();
        Retrofit retrofit = new Retrofit.Builder()
            .baseUrl("https://api.binance.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build();

        BinanceApiService binanceService = retrofit.create(BinanceApiService.class);
        
        binanceService.getKlinesWithEndTime(normalizedSymbol, binanceInterval, 1000, oldestTime - 1)
            .enqueue(new Callback<List<List<Object>>>() {
                @Override
                public void onResponse(Call<List<List<Object>>> call, Response<List<List<Object>>> response) {
                    if (response.isSuccessful() && response.body() != null && !response.body().isEmpty()) {
                        List<Candlestick> olderCandles = new ArrayList<>();
                        for (List<Object> kline : response.body()) {
                            try {
                                long openTime = ((Number) kline.get(0)).longValue();
                                double open = Double.parseDouble(kline.get(1).toString());
                                double openHigh = Double.parseDouble(kline.get(2).toString());
                                double openLow = Double.parseDouble(kline.get(3).toString());
                                double close = Double.parseDouble(kline.get(4).toString());
                                double volume = Double.parseDouble(kline.get(5).toString()); // Volume included for structural scans

                                olderCandles.add(new Candlestick(open, openHigh, openLow, close, volume, openTime));
                            } catch (Exception e) {
                                Log.e("MainActivity", "Error mapping older kline arrays: " + e.getMessage());
                            }
                        }

                        if (!olderCandles.isEmpty()) {
                            mCandles.addAll(0, olderCandles);
                            mChartView.notifyHistoryPrepended(olderCandles.size());
                            runLocalPatternAnalysis(); // Re-analyze after history prepended
                        }
                    } else {
                        Log.w("MainActivity", "No older historic candles found or API returned empty");
                    }
                    mIsLoadingHistory = false;
                    mSwipeRefresh.setRefreshing(false);
                }

                @Override
                public void onFailure(Call<List<List<Object>>> call, Throwable t) {
                    Log.e("MainActivity", "Failed to load older historic candles", t);
                    mIsLoadingHistory = false;
                    mSwipeRefresh.setRefreshing(false);
                }
            });
    }

    private void generateMoreSimulatedHistory() {
        if (mIsLoadingHistory) return;
        if (mCandles.isEmpty()) return;

        mIsLoadingHistory = true;
        mSwipeRefresh.setRefreshing(true);

        mSwipeRefresh.postDelayed(() -> {
            long oldestTime = mCandles.get(0).timestamp;
            double oldestOpen = mCandles.get(0).open;
            
            long timeStep = 3600 * 1000L; // default 1h
            double changeMult = 600.0;
            double wickMult = 200.0;
            
            String interval = mCurrentInterval;
            if ("1m".equals(interval)) {
                timeStep = 60 * 1000L;
                changeMult = 15.0;
                wickMult = 5.0;
            } else if ("5m".equals(interval)) {
                timeStep = 5 * 60 * 1000L;
                changeMult = 50.0;
                wickMult = 15.0;
            } else if ("15m".equals(interval)) {
                timeStep = 15 * 60 * 1000L;
                changeMult = 120.0;
                wickMult = 40.0;
            } else if ("1h".equals(interval)) {
                timeStep = 3600 * 1000L;
                changeMult = 600.0;
                wickMult = 200.0;
            } else if ("1D".equals(interval)) {
                timeStep = 24 * 3600 * 1000L;
                changeMult = 2500.0;
                wickMult = 800.0;
            }

            List<Candlestick> simulatedHistory = new ArrayList<>();
            double currentPrice = oldestOpen;
            long time = oldestTime - (40 * timeStep);

            for (int i = 0; i < 40; i++) {
                double change = (mRandom.nextDouble() - 0.52) * changeMult; // slightly downward backwards
                double open = currentPrice - change;
                double close = currentPrice;
                double high = Math.max(open, close) + mRandom.nextDouble() * wickMult;
                double low = Math.min(open, close) - mRandom.nextDouble() * wickMult;
                double volume = mRandom.nextDouble() * 500.0 + 100.0;

                simulatedHistory.add(new Candlestick(open, high, low, close, volume, time));
                currentPrice = open;
                time += timeStep;
            }

            mCandles.addAll(0, simulatedHistory);
            mChartView.notifyHistoryPrepended(simulatedHistory.size());
            
            mIsLoadingHistory = false;
            mSwipeRefresh.setRefreshing(false);
            runLocalPatternAnalysis(); // Re-analyze after history prepended
        }, 800);
    }

    private void generateSimulatedNews() {
        mNewsList.clear();
        mNewsList.add(new FeedItem("Bitcoin Breakthrough: Key Support Level Maintained", "Calculated SMA 20 holding steady | AI Confidence: 82% Grounded", "82% BUY", Color.parseColor("#10B981"), System.currentTimeMillis()));
        mNewsList.add(new FeedItem("Standard Securities Commission Index Approval Likely", "Over-the-counter institutional inflows surge on expectations", "92% UP", Color.parseColor("#10B981"), System.currentTimeMillis() - 4 * 3600 * 1000));
        mNewsList.add(new FeedItem("Technical Indicators Alert: RSI Oversold Zone Hit", "Bitcoin local bottom confirmed at EMA support bands", "OVERSOLD", Color.parseColor("#14B8A6"), System.currentTimeMillis() - 12 * 3600 * 1000));
        mNewsList.add(new FeedItem("Macro Reports Push Trading Volume to Historic highs", "Global market liquidity surges by 4.2% overnight", "VOLUME", Color.parseColor("#14B8A6"), System.currentTimeMillis() - 24 * 3600 * 1000));
    }

    private void updateIntervalButtonsUI(String selected) {
        int activeColor = Color.parseColor("#10B981");
        int inactiveColor = getResources().getColor(R.color.text_secondary_dark, null);
        int activeBorder = Color.parseColor("#10B981");
        int inactiveBorder = Color.parseColor("#0D1612");

        mBtn1m.setTextColor("1m".equals(selected) ? activeColor : inactiveColor);
        mBtn5m.setTextColor("5m".equals(selected) ? activeColor : inactiveColor);
        mBtn15m.setTextColor("15m".equals(selected) ? activeColor : inactiveColor);
        mBtn1h.setTextColor("1h".equals(selected) ? activeColor : inactiveColor);
        mBtn1D.setTextColor("1D".equals(selected) ? activeColor : inactiveColor);
        
        if (mBtn1m instanceof com.google.android.material.button.MaterialButton) {
            ((com.google.android.material.button.MaterialButton) mBtn1m).setStrokeColor(android.content.res.ColorStateList.valueOf("1m".equals(selected) ? activeBorder : inactiveBorder));
            ((com.google.android.material.button.MaterialButton) mBtn5m).setStrokeColor(android.content.res.ColorStateList.valueOf("5m".equals(selected) ? activeBorder : inactiveBorder));
            ((com.google.android.material.button.MaterialButton) mBtn15m).setStrokeColor(android.content.res.ColorStateList.valueOf("15m".equals(selected) ? activeBorder : inactiveBorder));
            ((com.google.android.material.button.MaterialButton) mBtn1h).setStrokeColor(android.content.res.ColorStateList.valueOf("1h".equals(selected) ? activeBorder : inactiveBorder));
            ((com.google.android.material.button.MaterialButton) mBtn1D).setStrokeColor(android.content.res.ColorStateList.valueOf("1D".equals(selected) ? activeBorder : inactiveBorder));
        }
    }

    private void setupListeners() {
        // Timeframe Interval Click listeners
        View.OnClickListener intervalClickListener = v -> {
            String interval = "1h";
            int id = v.getId();
            if (id == R.id.btn_interval_1m) {
                interval = "1m";
            } else if (id == R.id.btn_interval_5m) {
                interval = "5m";
            } else if (id == R.id.btn_interval_15m) {
                interval = "15m";
            } else if (id == R.id.btn_interval_1h) {
                interval = "1h";
            } else if (id == R.id.btn_interval_1d) {
                interval = "1D";
            }
            mCurrentInterval = interval;
            updateIntervalButtonsUI(interval);
            mChartView.clearCustomLines();
            loadBinanceCandles(interval);
        };
        mBtn1m.setOnClickListener(intervalClickListener);
        mBtn5m.setOnClickListener(intervalClickListener);
        mBtn15m.setOnClickListener(intervalClickListener);
        mBtn1h.setOnClickListener(intervalClickListener);
        mBtn1D.setOnClickListener(intervalClickListener);

        // Candlestick infinite scroll listener to load previous history
        mChartView.setOnScrollToLeftListener(() -> {
            if (mIsLoadingHistory) return;
            if (mIsSimulationMode) {
                generateMoreSimulatedHistory();
            } else {
                loadMoreHistory();
            }
        });

        // Settings dialog launch
        findViewById(R.id.btn_settings).setOnClickListener(v -> showSettingsDialog());

        // Indicator togglers
        View.OnClickListener indicatorToggleListener = v -> {
            mChartView.setEnabledIndicators(
                mEmaCheck.isChecked(),
                mSmaCheck.isChecked(),
                mBbCheck.isChecked(),
                mSrCheck.isChecked()
            );
        };
        mEmaCheck.setOnClickListener(indicatorToggleListener);
        mSmaCheck.setOnClickListener(indicatorToggleListener);
        mBbCheck.setOnClickListener(indicatorToggleListener);
        mSrCheck.setOnClickListener(indicatorToggleListener);

        mHideAllCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mChartView.setHideAllIndicators(isChecked);
            mEmaCheck.setEnabled(!isChecked);
            mSmaCheck.setEnabled(!isChecked);
            mBbCheck.setEnabled(!isChecked);
            mSrCheck.setEnabled(!isChecked);
        });

        // Alert Creator button
        mCreateAlertBtn.setOnClickListener(v -> {
            String alertValStr = mAlertPriceInput.getText().toString().trim();
            if (alertValStr.isEmpty()) {
                Toast.makeText(this, "Please enter a valid price threshold", Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                double targetPrice = Double.parseDouble(alertValStr);
                boolean isAbove = targetPrice > mCurrentPrice;

                mExecutor.execute(() -> {
                    PriceAlertEntity alert = new PriceAlertEntity(mCurrentSymbol, targetPrice, isAbove, true);
                    mDb.tradeDao().insertPriceAlert(alert);
                    
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Price alert created at $" + targetPrice, Toast.LENGTH_SHORT).show();
                        mAlertPriceInput.setText("");
                        updateAlertCount();
                    });
                });
            } catch (Exception e) {
                Toast.makeText(this, "Enter correct decimal values.", Toast.LENGTH_SHORT).show();
            }
        });

        // Tab selection filters
        mTabNewsBtn.setOnClickListener(v -> {
            mActiveTab = 0;
            mTabNewsBtn.setTextColor(Color.parseColor("#10B981"));
            mTabTradesBtn.setTextColor(getResources().getColor(R.color.text_secondary_dark, null));
            refreshFeedList();
        });

        mTabTradesBtn.setOnClickListener(v -> {
            mActiveTab = 1;
            mTabTradesBtn.setTextColor(Color.parseColor("#10B981"));
            mTabNewsBtn.setTextColor(getResources().getColor(R.color.text_secondary_dark, null));
            refreshFeedList();
        });

        // Swipe Pull action
        mSwipeRefresh.setOnRefreshListener(() -> {
            if (mActiveTab == 0) {
                // Simulate refresh news items
                generateSimulatedNews();
            } else {
                refreshFeedList();
            }
            mSwipeRefresh.setRefreshing(false);
        });

        // Voice button click
        mVoiceAssistantFab.setOnClickListener(v -> {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.RECORD_AUDIO}, 101);
            } else {
                openVoiceAssistant();
            }
        });

        // Set up long-click deletion handler inside RecyclerView feed adapter
        mFeedAdapter.setOnItemLongClickListener((position, item) -> {
            if (mActiveTab == 1) { // Only enable trades deletion when active on Trades Feed tab
                showDeleteTradeConfirmationDialog(item);
            }
        });

        // Recursively intercept scroll disallowance so sliding on the chart doesn't conflict with NestedScrollView
        mChartView.setOnTouchListener((v, event) -> {
            disallowParentIntercept(v, true);
            return false;
        });

        // Setup Alert counter initial state
        updateAlertCount();
    }

    private void disallowParentIntercept(View view, boolean disallow) {
        android.view.ViewParent parent = view.getParent();
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
            parent = parent.getParent();
        }
    }

    private void showDeleteTradeConfirmationDialog(FeedItem item) {
        new AlertDialog.Builder(this)
            .setTitle("Delete Trade Log")
            .setMessage("Are you sure you want to delete this trade log entry?")
            .setPositiveButton("Delete", (dialog, which) -> {
                mExecutor.execute(() -> {
                    List<PaperTradeTransaction> trades = mDb.tradeDao().getAllPaperTrades();
                    for (PaperTradeTransaction t : trades) {
                        if (t.timestamp == item.timestamp) {
                            mDb.tradeDao().deletePaperTrade(t.id);
                            break;
                        }
                    }
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Trade transaction deleted.", Toast.LENGTH_SHORT).show();
                        refreshFeedList();
                    });
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void handleVoiceDeletionCommand(String target) {
        mExecutor.execute(() -> {
            if ("LAST".equalsIgnoreCase(target)) {
                List<PaperTradeTransaction> trades = mDb.tradeDao().getAllPaperTrades();
                if (!trades.isEmpty()) {
                    mDb.tradeDao().deletePaperTrade(trades.get(0).id);
                    runOnUiThread(() -> {
                        Toast.makeText(this, "[AI VOICE COMMAND] Deleted the last trade log.", Toast.LENGTH_SHORT).show();
                        refreshFeedList();
                    });
                } else {
                    runOnUiThread(() -> Toast.makeText(this, "No trades found to delete.", Toast.LENGTH_SHORT).show());
                }
            } else if ("ALL".equalsIgnoreCase(target)) {
                mDb.tradeDao().deleteAllPaperTrades();
                runOnUiThread(() -> {
                    Toast.makeText(this, "[AI VOICE COMMAND] Cleared all trade logs.", Toast.LENGTH_SHORT).show();
                    refreshFeedList();
                });
            }
        });
    }

    private void openVoiceAssistant() {
        mActiveBottomSheet = VoiceAssistantBottomSheet.newInstance();
        mActiveBottomSheet.setListener(this);
        mActiveBottomSheet.setChartMetrics(mCandles, mCurrentPrice);
        mActiveBottomSheet.show(getSupportFragmentManager(), "VoiceAssistantBottomSheet");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @androidx.annotation.NonNull String[] permissions, @androidx.annotation.NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                openVoiceAssistant();
            } else {
                Toast.makeText(this, "Microphone permission denied. Speech input is disabled.", Toast.LENGTH_LONG).show();
                ErrorLogConsole.show(this, 
                    "PERMISSION RECORD_AUDIO DENIED", 
                    "Microphone hardware tracking blocked by security sandbox permission check.", 
                    "At Android framework checkSelfPermission Manifest.permission.RECORD_AUDIO", 
                    "Please navigate to Android Settings -> Apps -> TradeAnalyst -> Permissions -> Microphone and enable 'Allow' to activate speech-to-text live capabilities."
                );
            }
        }
    }

    private void updateAlertCount() {
        mExecutor.execute(() -> {
            List<PriceAlertEntity> alerts = mDb.tradeDao().getActivePriceAlerts();
            final int count = alerts.size();
            runOnUiThread(() -> mAlertsCountText.setText(count + " active alerts"));
        });
    }

    private void refreshFeedList() {
        if (mActiveTab == 0) {
            mFeedAdapter.setItems(mNewsList);
        } else {
            // Load from paper trades table from database!
            mExecutor.execute(() -> {
                List<PaperTradeTransaction> trades = mDb.tradeDao().getAllPaperTrades();
                List<FeedItem> tradeItems = new ArrayList<>();
                for (PaperTradeTransaction t : trades) {
                    int indicatorColor = "BUY".equalsIgnoreCase(t.action) ? Color.parseColor("#10B981") : Color.parseColor("#EF4444");
                    tradeItems.add(new FeedItem(
                        String.format("ORDER CONFIRMED: %s %s", t.action, t.symbol),
                        String.format("Executed at target price: $%,.2f | Confidence: %.0f%%", t.entryPrice, t.confidence),
                        t.action,
                        indicatorColor,
                        t.timestamp
                    ));
                }

                if (tradeItems.isEmpty()) {
                    tradeItems.add(new FeedItem(
                        "No Automated Trade Logged yet",
                        "Hold mic button below and voice order high-confidence BUY/SELL commands",
                        "EMPTY",
                        Color.parseColor("#14B8A6"),
                        System.currentTimeMillis()
                    ));
                }

                runOnUiThread(() -> mFeedAdapter.setItems(tradeItems));
            });
        }
    }

    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_settings, null);
        builder.setView(dialogView);

        AlertDialog dialog = builder.create();

        // Bind Settings Views
        EditText keyInput = dialogView.findViewById(R.id.settings_api_key_input);
        Spinner modelSpinner = dialogView.findViewById(R.id.settings_model_spinner);
        Button fetchModelsBtn = dialogView.findViewById(R.id.settings_btn_fetch_models);
        SwitchMaterial groundingSwitch = dialogView.findViewById(R.id.switch_grounding);
        SwitchMaterial themeSwitch = dialogView.findViewById(R.id.switch_theme);
        Button cancelBtn = dialogView.findViewById(R.id.settings_btn_cancel);
        Button saveBtn = dialogView.findViewById(R.id.settings_btn_save);

        // Read preconfigured configurations
        keyInput.setText(mPrefs.getApiKey());
        groundingSwitch.setChecked(mPrefs.isGroundingEnabled());
        themeSwitch.setChecked(mPrefs.isDarkThemeEnabled());

        // Fill Spinner models locally configured
        List<String> modelDisplayNames = new ArrayList<>();
        modelDisplayNames.add("Gemini 2.5 Flash Native Audio Preview");
        modelDisplayNames.add("Gemini 3.1 Flash Live Preview");

        List<String> modelIds = new ArrayList<>();
        modelIds.add("gemini-2.5-flash-native-audio-preview-12-2025");
        modelIds.add("gemini-3.1-flash-live-preview");

        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, modelDisplayNames) {
            @Override
            public android.view.View getView(int position, android.view.View convertView, android.view.ViewGroup parent) {
                android.view.View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(Color.BLACK);
                    ((TextView) v).setBackgroundColor(Color.WHITE);
                }
                return v;
            }

            @Override
            public android.view.View getDropDownView(int position, android.view.View convertView, android.view.ViewGroup parent) {
                android.view.View v = super.getDropDownView(position, convertView, parent);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(Color.BLACK);
                    ((TextView) v).setBackgroundColor(Color.WHITE);
                }
                return v;
            }
        };
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSpinner.setAdapter(spinnerAdapter);

        // Preselect current
        String savedModel = mPrefs.getModel();
        int savedIdx = modelIds.indexOf(savedModel);
        if (savedIdx >= 0) {
            modelSpinner.setSelection(savedIdx);
        }

        // Fetch Online Options
        fetchModelsBtn.setOnClickListener(v -> {
            String apiKey = keyInput.getText().toString().trim();
            if (apiKey.isEmpty()) {
                Toast.makeText(this, "Provide a Gemini key first before fetching online!", Toast.LENGTH_SHORT).show();
                return;
            }

            fetchModelsBtn.setText("Fetching...");
            GeminiRetrofitClient.getService().listModels(apiKey).enqueue(new Callback<GeminiRetrofitClient.ModelsQueryResponse>() {
                @Override
                public void onResponse(Call<GeminiRetrofitClient.ModelsQueryResponse> call, Response<GeminiRetrofitClient.ModelsQueryResponse> response) {
                    fetchModelsBtn.setText("Fetch Models Online");
                    if (response.isSuccessful() && response.body() != null && response.body().models != null) {
                        List<GeminiRetrofitClient.GeminiModelInfo> fetched = response.body().models;
                        
                        modelDisplayNames.clear();
                        modelIds.clear();

                        for (GeminiRetrofitClient.GeminiModelInfo m : fetched) {
                            // Filter valid generative models
                            if (m.name.contains("gemini") && m.supportedGenerationMethods.contains("generateContent")) {
                                String cleanName = m.name.replace("models/", "");
                                modelDisplayNames.add(m.displayName + " (" + cleanName + ")");
                                modelIds.add(cleanName);
                            }
                        }

                        // Rebind spinner
                        spinnerAdapter.notifyDataSetChanged();
                        Toast.makeText(MainActivity.this, "Successfully synced over-the-air Models!", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "Unauthorized API Key or invalid response.", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onFailure(Call<GeminiRetrofitClient.ModelsQueryResponse> call, Throwable t) {
                    fetchModelsBtn.setText("Fetch Models Online");
                    Toast.makeText(MainActivity.this, "Connection failed: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        });

        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        saveBtn.setOnClickListener(v -> {
            String key = keyInput.getText().toString().trim();
            mPrefs.saveApiKey(key);
            mPrefs.saveGroundingEnabled(groundingSwitch.isChecked());
            
            boolean themeChanged = mPrefs.isDarkThemeEnabled() != themeSwitch.isChecked();
            mPrefs.saveDarkThemeEnabled(themeSwitch.isChecked());

            int selectedPos = modelSpinner.getSelectedItemPosition();
            if (selectedPos < modelIds.size()) {
                mPrefs.saveModel(modelIds.get(selectedPos));
            }

            dialog.dismiss();
            Toast.makeText(this, "AI Configuration Updated!", Toast.LENGTH_SHORT).show();

            if (themeChanged) {
                // Instantly swap styling modes on the fly
                applyThemeStyles(themeSwitch.isChecked());
                if (mBound && mService != null && mService.getLiveAgentWebView() != null) {
                    mService.getLiveAgentWebView().evaluateJavascript("if (window.onThemeChanged) { window.onThemeChanged(" + themeSwitch.isChecked() + "); }", null);
                }
            }
        });

        dialog.show();
    }

    private void startLivePriceSimulator() {
        // Timed runnable simulating live cryptocurrency tickers
        android.os.Handler handler = new android.os.Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Tweak price by small random fraction
                double deviation = (mRandom.nextDouble() - 0.5) * 80;
                mCurrentPrice += deviation;

                mChartPriceText.setText(String.format("$%,.2f", mCurrentPrice));

                // Update last candle close & high/low
                if (!mCandles.isEmpty()) {
                    Candlestick last = mCandles.get(mCandles.size() - 1);
                    last.close = mCurrentPrice;
                    if (mCurrentPrice > last.high) last.high = mCurrentPrice;
                    if (mCurrentPrice < last.low) last.low = mCurrentPrice;
                    mChartView.invalidate();
                }

                // Check alert triggers
                checkPriceAlerts();
                
                // Track breakouts/invalidations against price tick updates
                runLocalPatternAnalysis();

                handler.postDelayed(this, 5000); // cycle every 5 seconds
            }
        }, 5000);
    }

    private void checkPriceAlerts() {
        mExecutor.execute(() -> {
            List<PriceAlertEntity> active = mDb.tradeDao().getActivePriceAlerts();
            for (PriceAlertEntity alert : active) {
                boolean trigger = false;
                if (alert.isAbove && mCurrentPrice >= alert.targetPrice) {
                    trigger = true;
                } else if (!alert.isAbove && mCurrentPrice <= alert.targetPrice) {
                    trigger = true;
                }

                if (trigger) {
                    mDb.tradeDao().setPriceAlertActive(alert.id, false);
                    final double val = alert.targetPrice;
                    runOnUiThread(() -> {
                        Toast.makeText(this, "🔔 PRICE ALERT TRIGGERED: " + alert.symbol + " is now $" + String.format("%.2f", mCurrentPrice) + " (Target was $" + val + ")", Toast.LENGTH_LONG).show();
                        updateAlertCount();
                    });
                }
            }
        });
    }

    // --- WebView Bridge Handlers & Methods ---

    public WebView getLiveAgentWebView() {
        if (mBound && mService != null) {
            return mService.getLiveAgentWebView();
        }
        return null;
    }

    public TradingPreferences getPrefs() {
        return mPrefs;
    }

    /**
     * Overloaded method to construct a context frame of exact selected candle lookup depth.
     * Enriches JSON structure with volume details to support volumetric confirmations.
     */
    @Override
    public String getChartContext(int lookback) {
        if (mCandles == null || mCandles.isEmpty()) {
            return "{\"error\":\"Historical data array currently empty.\"}";
        }

        int actualLookback = Math.min(lookback, mCandles.size());
        int startIndex = mCandles.size() - actualLookback;

        List<Candlestick> subList = mCandles.subList(startIndex, mCandles.size());

        // Calculate indicators specifically for the window or the full series and slice them
        double[] rsiAll = IndicatorsEngine.calculateRSI(mCandles, 14);
        double[] emaAll = IndicatorsEngine.calculateEMA(mCandles, 20);
        double[] smaAll = IndicatorsEngine.calculateSMA(mCandles, 20);

        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"symbol\":\"").append(mCurrentSymbol).append("\",");
        json.append("\"interval\":\"").append(mCurrentInterval).append("\",");
        json.append("\"currentPrice\":").append(mCurrentPrice).append(",");
        json.append("\"candles\":[");

        for (int i = 0; i < subList.size(); i++) {
            int absoluteIndex = startIndex + i;
            Candlestick c = subList.get(i);

            double rsiVal = rsiAll.length > absoluteIndex ? rsiAll[absoluteIndex] : 50.0;
            double emaVal = emaAll.length > absoluteIndex ? emaAll[absoluteIndex] : c.close;
            double smaVal = smaAll.length > absoluteIndex ? smaAll[absoluteIndex] : c.close;

            double openVal = Math.round(c.open * 100.0) / 100.0;
            double highVal = Math.round(c.high * 100.0) / 100.0;
            double lowVal = Math.round(c.low * 100.0) / 100.0;
            double closeVal = Math.round(c.close * 100.0) / 100.0;
            double volumeVal = Math.round(c.volume * 100.0) / 100.0; // Round volume elements
            double rsiValRounded = Math.round(rsiVal * 10.0) / 10.0;
            double emaValRounded = Math.round(emaVal * 100.0) / 100.0;
            double smaValRounded = Math.round(smaVal * 100.0) / 100.0;

            json.append("{");
            json.append("\"index\":").append(i).append(",");
            json.append("\"open\":").append(openVal).append(",");
            json.append("\"high\":").append(highVal).append(",");
            json.append("\"low\":").append(lowVal).append(",");
            json.append("\"close\":").append(closeVal).append(",");
            json.append("\"volume\":").append(volumeVal).append(","); // Enriched volume data
            json.append("\"timestamp\":").append(c.timestamp).append(",");
            json.append("\"rsi\":").append(rsiValRounded).append(",");
            json.append("\"ema\":").append(emaValRounded).append(",");
            json.append("\"sma\":").append(smaValRounded);
            json.append("}");

            if (i < subList.size() - 1) {
                json.append(",");
            }
        }
        json.append("]");
        json.append("}");
        return json.toString();
    }

    public String getChartContext() {
        return getChartContext(10);
    }

    @Override
    public String getLatestPatternCandidate() {
        return getLatestPatternCandidateJson();
    }

    public String getLatestPatternCandidateJson() {
        if (mLatestPatternCandidate != null) {
            try {
                return new Gson().toJson(mLatestPatternCandidate);
            } catch (Exception e) {
                Log.e("MainActivity", "Error serializing latest pattern candidate: " + e.getMessage());
            }
        }
        return "{}";
    }

    /**
     * Executes local multi-dimensional pattern analysis in a safe, non-blocking asynchronous thread.
     */
    private void runLocalPatternAnalysis() {
        if (mCandles == null || mCandles.isEmpty()) {
            return;
        }

        mExecutor.execute(() -> {
            try {
                // 1. Detect Swing Points (Peaks and Troughs)
                // Left strength: 5, Right strength: 5, Proximity threshold: 3
                List<SwingPoint> swingPoints = SwingEngine.detectSwingPoints(mCandles, 5, 5, 3);
                if (swingPoints == null || swingPoints.isEmpty()) {
                    mLatestPatternCandidate = null;
                    return;
                }

                // 2. Initialize the 19 mathematical pattern detectors
                List<BasePatternDetector> detectors = new ArrayList<>();
                detectors.add(new ChannelDetector());
                detectors.add(new FallingWedgeDetector());
                detectors.add(new RectangleDetector());
                detectors.add(new DoubleTopDetector());
                detectors.add(new RoundingTopDetector());
                detectors.add(new InverseHeadAndShouldersDetector());
                detectors.add(new DescendingTriangleDetector());
                detectors.add(new DoubleBottomDetector());
                detectors.add(new TripleBottomDetector());
                detectors.add(new SymmetricalTriangleDetector());
                detectors.add(new CupAndHandleDetector());
                detectors.add(new BearFlagDetector());
                detectors.add(new TripleTopDetector());
                detectors.add(new RoundingBottomDetector());
                detectors.add(new RisingWedgeDetector());
                detectors.add(new AscendingTriangleDetector());
                detectors.add(new PennantDetector());
                detectors.add(new BullFlagDetector());
                detectors.add(new HeadAndShouldersDetector());

                // 3. Scan the historical series and collect valid pattern matches
                List<ChartPattern> detectedPatterns = new ArrayList<>();
                for (BasePatternDetector detector : detectors) {
                    try {
                        List<ChartPattern> matches = detector.detect(mCandles, swingPoints);
                        if (matches != null && !matches.isEmpty()) {
                            detectedPatterns.addAll(matches);
                        }
                    } catch (Exception ex) {
                        Log.e("MainActivity", "Failed scan execution for " + detector.getClass().getSimpleName(), ex);
                    }
                }

                // 4. Identify and cache the single highest-confidence candidate
                if (!detectedPatterns.isEmpty()) {
                    ChartPattern highestConfidencePattern = null;
                    double maxConfidence = -1.0;

                    for (ChartPattern pattern : detectedPatterns) {
                        if (pattern.getConfidence() > maxConfidence) {
                            maxConfidence = pattern.getConfidence();
                            highestConfidencePattern = pattern;
                        }
                    }

                    if (highestConfidencePattern != null) {
                        List<ChartPattern> patternWrapper = new ArrayList<>();
                        patternWrapper.add(highestConfidencePattern);

                        String recommendation = "HOLD";
                        if (highestConfidencePattern.getBias() != null) {
                            if (highestConfidencePattern.getBias().equalsIgnoreCase("BULLISH")) {
                                recommendation = "BUY";
                            } else if (highestConfidencePattern.getBias().equalsIgnoreCase("BEARISH")) {
                                recommendation = "SELL";
                            }
                        }

                        ChartPatternSummary summary = new ChartPatternSummary(
                            highestConfidencePattern.getType(),
                            highestConfidencePattern.getConfidence(),
                            recommendation
                        );

                        ChartPatternResponse response = new ChartPatternResponse(patternWrapper, summary);
                        response.setLookback(mCandles.size());
                        mLatestPatternCandidate = response;
                        
                        Log.d("MainActivity", "Cached winning pattern candidate: " 
                            + highestConfidencePattern.getType() 
                            + " | Confidence: " + highestConfidencePattern.getConfidence() + "%");
                    }
                } else {
                    mLatestPatternCandidate = null;
                }

            } catch (Exception e) {
                Log.e("MainActivity", "Local mathematical scan loop execution failure", e);
            }
        });
    }

    /**
     * Triggers dynamic rebuilding of local klines upon receiving voice timeframe-swaps
     */
    public void onTimeframeCommandReceived(String interval) {
        runOnUiThread(() -> {
            mCurrentInterval = interval;
            updateIntervalButtonsUI(interval);
            mChartView.clearCustomLines();
            loadBinanceCandles(interval);
            
            // Loop Closure: instantly sync updated context frame back to webview WebSocket session
            if (mBound && mService != null && mService.getLiveAgentWebView() != null) {
                mService.getLiveAgentWebView().evaluateJavascript("if (window.updateLiveContextWithNewTimeframe) { window.updateLiveContextWithNewTimeframe(); }", null);
            }
        });
    }

    @Override
    public void onLiveWebSocketConnected() {
        mIsLiveSessionActive = true; // State Sync [1]
        runOnUiThread(() -> {
            if (mActiveBottomSheet != null) {
                mActiveBottomSheet.updateStatusText("Connected to Gemini Live Model");
            }
        });
    }

    @Override
    public void onLiveWebSocketDisconnected(String reason) {
        mIsLiveSessionActive = false; // State Sync [1]
        runOnUiThread(() -> {
            if (mActiveBottomSheet != null) {
                mActiveBottomSheet.updateStatusText("Disconnected: " + reason);
                mActiveBottomSheet.resetMicButton();
            }
        });
    }

    @Override
    public void onLiveTranscriptReceived(String sender, String message) {
        runOnUiThread(() -> {
            // Safe verification check: Is bottom sheet dialog active and attached [2]
            if (mActiveBottomSheet != null && mActiveBottomSheet.isAdded() && !mActiveBottomSheet.isStateSaved()) {
                mActiveBottomSheet.appendChatMessage(sender, message);
            } else {
                // Safe asynchronous DB fallback to prevent RejectedExecutionExceptions on closed views [2]
                saveConversationToDb(sender, message);
            }
        });
    }

    /**
     * Fallback asynchronously saves conversation logs directly to database when bottom sheet is dismissed [2]
     */
    private void saveConversationToDb(String sender, String message) {
        mExecutor.execute(() -> {
            try {
                ConversationEntity entity = new ConversationEntity(sender, message, System.currentTimeMillis());
                mDb.tradeDao().insertConversation(entity);
            } catch (Exception e) {
                Log.e("MainActivity", "Failed to write late transcription context directly to Room DB", e);
            }
        });
    }

    @Override
    public void onLiveCommandReceived(String type, String payload) {
        runOnUiThread(() -> {
            if ("SIGNAL".equalsIgnoreCase(type)) {
                // Parse and execute local paper trade order automatically
                parseAndLogPaperTrade(payload);
            } else if ("INDICATOR".equalsIgnoreCase(type)) {
                // Draw dynamic custom indicator overlay on native chart instantly
                parseAndDrawCustomIndicator(payload);
            } else if ("TIMEFRAME".equalsIgnoreCase(type)) {
                // Change timeframe natively via voice command tag
                onTimeframeCommandReceived(payload);
            } else if ("CLEAR_CUSTOM_LINES".equalsIgnoreCase(type)) {
                // Wipe custom indicator visual overlays on canvas instantly
                mChartView.clearCustomLines();
                ChartPatternManager.clearActivePatterns(mChartView);
            } else if ("DELETE".equalsIgnoreCase(type)) {
                // Trigger programmatic deletion via robot voice command
                handleVoiceDeletionCommand(payload);
            } else if ("PATTERN".equalsIgnoreCase(type)) {
                // Route to ChartPatternManager to decode JSON, translate points, and draw on view
                ChartPatternManager.processPatternsJson(this, mChartView, payload);
            }
        });
    }

    private void parseAndLogPaperTrade(String payload) {
        try {
            // Expected payload format: "BUY|85|64750.00" (Action|Confidence|Price)
            String[] parts = payload.split("\\|");
            if (parts.length >= 3) {
                String action = parts[0].trim();
                double confidence = Double.parseDouble(parts[1].trim());
                double targetPrice = Double.parseDouble(parts[2].trim());

                mExecutor.execute(() -> {
                    PaperTradeTransaction trade = new PaperTradeTransaction(
                        mCurrentSymbol,
                        action.toUpperCase(),
                        confidence,
                        targetPrice,
                        System.currentTimeMillis()
                    );
                    mDb.tradeDao().insertPaperTrade(trade);

                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "[AI AUTOMATION] Trade EXECUTED! " + action + " BTC at $" + targetPrice + " (" + confidence + "% Confidence)", Toast.LENGTH_LONG).show();
                        onAutomaticOrderExecuted(trade);
                    });
                });
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Failed to parse auto-trade payload: " + payload, e);
        }
    }

    private void parseAndDrawCustomIndicator(String payload) {
        try {
            // Expected payload format: "Label|Price"
            String[] parts = payload.split("\\|");
            if (parts.length >= 2) {
                String label = parts[0].trim();
                double price = Double.parseDouble(parts[1].trim());
                onCustomIndicatorGenerated(label, price, Color.parseColor("#00E676"));
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Failed to parse custom indicator payload: " + payload, e);
        }
    }

    // --- VoiceAssistantBottomSheet Callback Handlers ---

    @Override
    public void onAutomaticOrderExecuted(PaperTradeTransaction trade) {
        // Automatically inserts are triggered by bottom sheet, we just update the views!
        refreshFeedList();
        
        // Let's add it to News list feed too to make user feel the execution
        mNewsList.add(0, new FeedItem(
            "[AUTO BOT EXECUTION] LONG " + trade.symbol + " SUCCESSFUL",
            "Automatic paper trading engine successfully triggered at target Entry: $" + trade.entryPrice,
            "BOT EXEC",
            Color.parseColor("#10B981"),
            System.currentTimeMillis()
        ));
        refreshFeedList();
    }

    @Override
    public void onCustomIndicatorGenerated(String label, double price, int color) {
        // Draw the indicator custom level generated by AI instantly onto the chart canvas!
        mChartView.addCustomLine(label, price, color);
    }

    @Override
    public void onRefreshRequired() {
        refreshFeedList();
    }
    
    // Public getter to expose the background connection status to sheet fragments [1]
    public boolean isLiveSessionActive() {
        return mIsLiveSessionActive;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
        mExecutor.shutdown();
    }
}