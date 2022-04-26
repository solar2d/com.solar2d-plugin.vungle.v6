/*
 * The MIT License (MIT)
 * 
 * Copyright (c) 2013-2014 Vungle
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package plugin.vungle.v6;

import java.util.Locale;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;

import com.ansca.corona.CoronaEnvironment;
import com.ansca.corona.CoronaLua;
import com.ansca.corona.CoronaLuaEvent;
import com.ansca.corona.CoronaRuntime;
import com.ansca.corona.CoronaRuntimeListener;
import com.ansca.corona.CoronaRuntimeTask;
import com.ansca.corona.CoronaRuntimeTaskDispatcher;
import com.naef.jnlua.JavaFunction;
import com.naef.jnlua.LuaState;
import com.naef.jnlua.NamedJavaFunction;
import com.vungle.warren.AdConfig;
import com.vungle.warren.BuildConfig;
import com.vungle.warren.InitCallback;
import com.vungle.warren.LoadAdCallback;
import com.vungle.warren.PlayAdCallback;
import com.vungle.warren.Vungle;
import com.vungle.warren.Vungle;
import com.vungle.warren.VungleApiClient;
import com.vungle.warren.error.VungleException;

import android.util.Log;
import java.util.*;

/**
 * <p>Vungle AdsProvider plugin.</p>
 */
public class LuaLoader implements JavaFunction, CoronaRuntimeListener {
	private static final String TAG = "VungleCorona";
	private static final String VERSION = "6.10.6";//plugin version. Do not delete this comment
	private static final Locale LOCALE = Locale.US;

	// LUA method names
	static final String GET_VERSION_STRING_METHOD = "getVersionString";
	static final String INIT_METHOD = "init";
	static final String IS_AD_AVAILABLE_METHOD = "isLoaded";
	static final String SHOW_METHOD = "show";
    static final String LOAD_METHOD = "load";
    static final String CLOSE_METHOD = "closeAd";
    static final String CLEAR_CACHE_METHOD = "clearCache";
    static final String CLEAR_SLEEP_METHOD = "clearSleep";
    static final String ENABLE_LOGGING_METHOD = "enableLogging";
    static final String SUBSCRIBE_HB_METHOD = "subscribeHB";
    static final String UPDATE_CONSENT_STATUS = "setHasUserConsent";
    static final String GET_CONSENT_STATUS = "getConsentStatus";
    static final String GET_CONSENT_MESSAGE_VERSION = "getConsentMessageVersion";

	// events
	static final String EVENT_PHASE_KEY = "phase";
	static final String AD_DISPLAYED_EVENT_PHASE = "displayed";
    static final String AD_COMPLETED_EVENT_PHASE = "completed";
	static final String AD_FAILED_PHASE = "failed";
    static final String AD_LOADED_EVENT_PHASE = "loaded";
    static final String AD_INITIALIZE_PHASE = "init";
    static final String AD_CLICKED_PHASE = "clicked";
    static final String AD_REWARD_PHASE = "userReceivedReward";
    static final String AD_VUNGLE_CREATIVE_EVENT_PHASE = "adVungleCreative";

    static final String EVENT_TYPE_KEY = "type";
    static final String EVENT_TYPE_VALUE = "vungle";

    static final String AD_VIEW_IS_COMPLETED_VIEW_KEY = "completedView";
    static final String AD_PLACEMENT_ID_KEY = "placementID";
	static final String CORONA_AD_PROVIDER_NAME = "vungle";

    
    static final String AD_RESPONSE_KEY = "response";

	private static final String DEFAULT_CORONA_APPLICATION_ID = "defaultCoronaApplicationId";

	/**
	 * application ID for ad network
	 */
	private String applicationId;

	// lua state
	CoronaRuntimeTaskDispatcher taskDispatcher;
	int luaListener = CoronaLua.REFNIL;

	// N.B. not called on UI thread 
	@Override
	public int invoke(LuaState luaState) {
		final String libName = luaState.toString(1);
		luaState.register(libName, new NamedJavaFunction[] {
			new GetVersionStringWrapper(),
			new InitWrapper(),
			new IsAdAvailableWrapper(),
			new ShowWrapper(),
            new LoadWrapper(),
            new UpdateConsentStatusWrapper(),
            new GetConsentStatusWrapper(),  //hidden
            new GetConsentMessageVersionWrapper(), //hidden
            new CloseWrapper(), //deperated
            new ClearCacheWrapper(),//hidden
            new ClearSleepWrapper(),//hidden
            new EnableLoggingWrapper(),
            new SubscribeHBWrapper(), //hidden
		});
		// add fallback test app id
		luaState.pushString(DEFAULT_CORONA_APPLICATION_ID);
		luaState.setField(-2, "testAppId");
		return 1;
	}

	private class InitWrapper implements NamedJavaFunction {
		private InitWrapper() {}
		@Override
		public String getName() {
			return INIT_METHOD;
		}
		@Override
		public int invoke(LuaState luaState) {
			return init(luaState);
		}
	}

    private LuaState createBaseEvent(CoronaRuntime coronaRuntime, String eventPhase, boolean isError) {
        final LuaState luaState = coronaRuntime.getLuaState();
        CoronaLua.newEvent(luaState, CoronaLuaEvent.ADSREQUEST_TYPE);
        luaState.pushString(CORONA_AD_PROVIDER_NAME);
        luaState.setField(-2, CoronaLuaEvent.PROVIDER_KEY);
        luaState.pushBoolean(isError);
        luaState.setField(-2, CoronaLuaEvent.ISERROR_KEY);
        luaState.pushString(eventPhase);
        luaState.setField(-2, EVENT_PHASE_KEY);
        luaState.pushString(EVENT_TYPE_VALUE);
        luaState.setField(-2, EVENT_TYPE_KEY);
        return luaState;
    }

	/**
	 * <p>Initializes this ad provider.</p>
	 * 
	 * <p>In Lua, returns <code>true</code> if successful; <code>false</code> otherwise.
	 * 
	 * @param luaState [1: pluginName,
     *                  2: pubAppId,placementIds,
	 *                  3: listener (optional)]
	 * @return <code>1</code> (the number of return values).
	 */
	public int init(LuaState luaState) {
		int nextArg = 1;
		String applicationId = this.applicationId;
		if(luaState.isString(nextArg)) {
            applicationId = luaState.toString(nextArg);
			nextArg++;
		}

        
		if (CoronaLua.isListener(luaState, nextArg, CoronaLuaEvent.ADSREQUEST_TYPE)) {
			luaListener = CoronaLua.newRef(luaState, nextArg);
		}
		nextArg++;

        //VungleApiClient.addWrapperInfo(VungleApiClient.WrapperFramework.corona, VERSION);
        Vungle.init(applicationId, CoronaEnvironment.getApplicationContext(), new InitCallback() {
            @Override
            public void onSuccess() {
                if (luaListener != CoronaLua.REFNIL)
                    taskDispatcher.send(new CoronaRuntimeTask() {
                        @Override
                        public void executeUsing(CoronaRuntime coronaRuntime) {
                            final String eventPhase = AD_INITIALIZE_PHASE;
                            try {
                                final LuaState asyncLuaState = createBaseEvent(coronaRuntime, eventPhase, false);
                                CoronaLua.dispatchEvent(asyncLuaState, luaListener, 0);
                            } catch (Exception exception) {
                                Log.e(TAG, "Unable to dispatch event " + eventPhase, exception);
                            }
                        }
                    });
            }
            @Override
            public void onError(VungleException exception) {
                if (luaListener == CoronaLua.REFNIL) return;
                taskDispatcher.send(new CoronaRuntimeTask() {
                    @Override
                    public void executeUsing(CoronaRuntime coronaRuntime) {
                        final String eventPhase = AD_INITIALIZE_PHASE;
                        try {
                            final LuaState asyncLuaState = createBaseEvent(coronaRuntime, eventPhase, true);
                            CoronaLua.dispatchEvent(asyncLuaState, luaListener, 0);
                        } catch (Exception exception) {
                            Log.e(TAG, "Unable to dispatch event " + eventPhase, exception);
                        }
                    }
                });
            }

            public void onAutoCacheAdAvailable(final String placementId) {
                if (luaListener == CoronaLua.REFNIL) return;
                taskDispatcher.send(new CoronaRuntimeTask() {
                    @Override
                    public void executeUsing(CoronaRuntime coronaRuntime) {
                        final String eventPhase = AD_LOADED_EVENT_PHASE;
                        try {
                            final LuaState asyncLuaState = createBaseEvent(coronaRuntime, eventPhase, false);
                            asyncLuaState.pushString(placementId);
                            asyncLuaState.setField(-2, AD_PLACEMENT_ID_KEY);

                            CoronaLua.dispatchEvent(asyncLuaState, luaListener, 0);
                        } catch (Exception exception) {
                            Log.e(TAG, "Unable to dispatch event " + eventPhase, exception);
                        }
                    }
                });
            }
        });
		luaState.pushBoolean(true);
		return 1;
	}

	private class GetVersionStringWrapper implements NamedJavaFunction {
		private GetVersionStringWrapper() {}
		@Override
		public String getName() {
			return GET_VERSION_STRING_METHOD;
		}
		@Override
		public int invoke(LuaState luaState) {
            luaState.pushString(VERSION + " (" + com.vungle.warren.BuildConfig.VERSION_NAME + ")");
            return 1;
		}
	}

	private class IsAdAvailableWrapper implements NamedJavaFunction {
		IsAdAvailableWrapper() {}
		@Override
		public String getName() {
			return IS_AD_AVAILABLE_METHOD;
		}
		@Override
		public int invoke(LuaState luaState) {
            luaState.pushBoolean(Vungle.canPlayAd(luaState.toString(1)));
			return 1;
		}
	}
    
    private class CloseWrapper implements NamedJavaFunction {
        CloseWrapper() {}
        @Override
        public String getName() {
            return CLOSE_METHOD;
        }
        @Override
        public int invoke(LuaState luaState) {
            Log.e(TAG, "Close api is no logger supported");

            return 1;
        }
    }
    
    private class LoadWrapper implements NamedJavaFunction {
        LoadWrapper() {}
        @Override
        public String getName() {
            return LOAD_METHOD;
        }
        @Override
        public int invoke(LuaState luaState) {
            Vungle.loadAd(luaState.toString(1), new LoadAdCallback() {
                @Override
                public void onAdLoad(final String placementId) {
                    if (luaListener == CoronaLua.REFNIL) return;
                    taskDispatcher.send(new CoronaRuntimeTask() {
                        @Override
                        public void executeUsing(CoronaRuntime coronaRuntime) {
                            final String eventPhase = AD_LOADED_EVENT_PHASE;
                            try {
                                final LuaState asyncLuaState = createBaseEvent(coronaRuntime, eventPhase, false);
                                asyncLuaState.pushString(placementId);
                                asyncLuaState.setField(-2, AD_PLACEMENT_ID_KEY);

                                CoronaLua.dispatchEvent(asyncLuaState, luaListener, 0);
                            } catch (Exception exception) {
                                Log.e(TAG, "Unable to dispatch event " + eventPhase, exception);
                            }
                        }
                    });
                }
                public void onError(String placementId, VungleException exception) {
                    if (luaListener == CoronaLua.REFNIL) return;
                    taskDispatcher.send(new CoronaRuntimeTask() {
                        @Override
                        public void executeUsing(CoronaRuntime coronaRuntime) {
                            final String eventPhase = AD_FAILED_PHASE;
                            try {
                                final LuaState asyncLuaState = createBaseEvent(coronaRuntime, eventPhase, true);
                                asyncLuaState.pushString(placementId);
                                asyncLuaState.setField(-2, AD_PLACEMENT_ID_KEY);

                                asyncLuaState.pushString(exception.getLocalizedMessage());
                                asyncLuaState.setField(-2, AD_RESPONSE_KEY);
                                CoronaLua.dispatchEvent(asyncLuaState, luaListener, 0);
                            } catch (Exception exception) {
                                Log.e(TAG, "Unable to dispatch event " + eventPhase, exception);
                            }
                        }
                    });
                }
            });
            return 1;
        }
    }
    
    private class UpdateConsentStatusWrapper implements NamedJavaFunction {
        UpdateConsentStatusWrapper() {}
        @Override
        public String getName() {
            return UPDATE_CONSENT_STATUS;
        }
        @Override
        public int invoke(LuaState luaState) {
            String version = "1.0.0";
            if(luaState.isString(2)){
                version = luaState.toString(2);
            }
            Vungle.updateConsentStatus(luaState.toBoolean(1)?Vungle.Consent.OPTED_IN:Vungle.Consent.OPTED_OUT,version);
            return 1;
        }
    }

    private class GetConsentStatusWrapper implements NamedJavaFunction {
        GetConsentStatusWrapper() {}
        @Override
        public String getName() {
            return GET_CONSENT_STATUS;
        }
        @Override
        public int invoke(LuaState luaState) {
            Vungle.Consent consent = Vungle.getConsentStatus();
            if (consent == null)  {
                luaState.pushInteger(0);
                return 1;
            }
            luaState.pushInteger((consent == Vungle.Consent.OPTED_IN)?1:2);
            return 1;
        }
    }
    private class GetConsentMessageVersionWrapper implements NamedJavaFunction {
        GetConsentMessageVersionWrapper(){}
        @Override
        public  String getName(){
            return  GET_CONSENT_MESSAGE_VERSION;
        }
        @Override
        public int invoke(LuaState luaState) {
            String consentVersion = Vungle.getConsentMessageVersion();
            luaState.pushString(consentVersion);
            return 1;
        }
    }
    
    private class ShowWrapper implements NamedJavaFunction {
        ShowWrapper() {}
        @Override
        public String getName() {
            return SHOW_METHOD;
        }
        @Override
        public int invoke(LuaState luaState) {
            return show(luaState);
        }
    }
    
    public int show(LuaState luaState) {
        final String METHOD_NAME = SHOW_METHOD + "(): ";
        final AdConfig adConfig = new AdConfig();
        final int numberOfArguments = luaState.getTop();
        String placementId = "";
        // get the lower case ad type if it exists:
        if (numberOfArguments >= 1 && luaState.isTable(1)) {
            luaState.getField(1, "placementId");
            if (luaState.isNil(-1)) {
                return -1;
            }
            placementId = luaState.toString(-1);
            luaState.pop(1);
            luaState.getField(1, "isAutoRotation");
            if (!luaState.isNil(-1)) {
                if(luaState.toBoolean(-1) == true){
                    adConfig.setAdOrientation(AdConfig.AUTO_ROTATE);
                }
            }
            luaState.pop(1);
            luaState.getField(1, "isSoundEnabled");
            if (!luaState.isNil(-1)) {
                adConfig.setMuted(!luaState.toBoolean(-1));
            }
            luaState.pop(1);
            luaState.getField(1, "immersive");
            if (!luaState.isNil(-1)) {
                adConfig.setImmersiveMode(luaState.toBoolean(-1));
            }
            luaState.pop(1);
            String userId = null;
            String title = null;
            String body = null;
            String close = null;
            String keepWatching = null;
            luaState.getField(1, "userTag");
            if (!luaState.isNil(-1)) {
                userId = luaState.toString(-1);
            }
            luaState.pop(1);
            luaState.getField(1, "alertTitle");
            if (!luaState.isNil(-1)) {
                title = luaState.toString(-1);
            }
            luaState.pop(1);
            luaState.getField(1, "alertText");
            if (!luaState.isNil(-1)) {
                body = luaState.toString(-1);
            }
            luaState.pop(1);
            luaState.getField(1, "alertClose");
            if (!luaState.isNil(-1)) {
                close = luaState.toString(-1);
            }
            luaState.pop(1);
            luaState.getField(1, "alertContinue");
            if (!luaState.isNil(-1)) {
                keepWatching = luaState.toString(-1);
            }
            luaState.pop(1);
            Vungle.setIncentivizedFields(userId, title, body, keepWatching, close);
            luaState.getField(1, "ordinal");
            if (!luaState.isNil(-1)) {
                String ordinal = luaState.toString(-1);
                try {
                    adConfig.setOrdinal(Integer.parseInt(ordinal));
                } catch (Exception e) {}
            }
            luaState.pop(1);
        }
        Vungle.playAd(placementId, adConfig, new PlayAdCallback() {
            @Override
            public void onAdStart(final String placementId) {
                if (luaListener == CoronaLua.REFNIL) return;
                taskDispatcher.send(new CoronaRuntimeTask() {
                    @Override
                    public void executeUsing(CoronaRuntime coronaRuntime) {
                        final String eventType = AD_DISPLAYED_EVENT_PHASE;
                        try {
                            final LuaState asyncLuaState = createBaseEvent(coronaRuntime, eventType, false);
                            asyncLuaState.pushString(placementId);
                            asyncLuaState.setField(-2, AD_PLACEMENT_ID_KEY);
                            CoronaLua.dispatchEvent(asyncLuaState, luaListener, 0);
                        } catch (Exception exception) {
                            Log.e(TAG, "Unable to dispatch event " + eventType, exception);
                        }
                    }
                });
            }
            @Override
            public void onAdEnd(final String placementId, final boolean wasSuccessfulView, final boolean wasCallToActionClicked) {
                if (luaListener == CoronaLua.REFNIL) return;
                taskDispatcher.send(new CoronaRuntimeTask() {
                    @Override
                    public void executeUsing(CoronaRuntime coronaRuntime) {
                        final String eventPhase = AD_COMPLETED_EVENT_PHASE;
                        try {
                            final LuaState asyncLuaState = createBaseEvent(coronaRuntime, eventPhase, false);
                            asyncLuaState.pushString(placementId);
                            asyncLuaState.setField(-2, AD_PLACEMENT_ID_KEY);


                            CoronaLua.dispatchEvent(asyncLuaState, luaListener, 0);
                        } catch (Exception exception) {
                            Log.e(TAG, "Unable to dispatch event " + eventPhase, exception);
                        }
                    }
                });
            }
            @Override
            public void creativeId(String creativeId) {
                if (luaListener == CoronaLua.REFNIL) return;
                taskDispatcher.send(new CoronaRuntimeTask() {
                    @Override
                    public void executeUsing(CoronaRuntime coronaRuntime) {
                        final String eventPhase = AD_VUNGLE_CREATIVE_EVENT_PHASE;
                        try {
                            final LuaState asyncLuaState = createBaseEvent(coronaRuntime, eventPhase, false);
                            asyncLuaState.pushString(creativeId);
                            asyncLuaState.setField(-2, "creativeID");

                            CoronaLua.dispatchEvent(asyncLuaState, luaListener, 0);
                        } catch (Exception exception) {
                            Log.e(TAG, "Unable to dispatch event " + eventPhase, exception);
                        }
                    }
                });
            }
            @Override
            public void onAdEnd(String placementId) {
                if (luaListener == CoronaLua.REFNIL) return;
                taskDispatcher.send(new CoronaRuntimeTask() {
                    @Override
                    public void executeUsing(CoronaRuntime coronaRuntime) {
                        final String eventPhase = AD_COMPLETED_EVENT_PHASE;
                        try {
                            final LuaState asyncLuaState = createBaseEvent(coronaRuntime, eventPhase, false);
                            asyncLuaState.pushString(placementId);
                            asyncLuaState.setField(-2, AD_PLACEMENT_ID_KEY);

                            CoronaLua.dispatchEvent(asyncLuaState, luaListener, 0);
                        } catch (Exception exception) {
                            Log.e(TAG, "Unable to dispatch event " + eventPhase, exception);
                        }
                    }
                });
            }
            @Override
            public void onAdClick(String placementId) {
                if (luaListener == CoronaLua.REFNIL) return;
                taskDispatcher.send(new CoronaRuntimeTask() {
                    @Override
                    public void executeUsing(CoronaRuntime coronaRuntime) {
                        final String eventPhase = AD_CLICKED_PHASE;
                        try {
                            final LuaState asyncLuaState = createBaseEvent(coronaRuntime, eventPhase, false);
                            asyncLuaState.pushString(placementId);
                            asyncLuaState.setField(-2, AD_PLACEMENT_ID_KEY);

                            CoronaLua.dispatchEvent(asyncLuaState, luaListener, 0);
                        } catch (Exception exception) {
                            Log.e(TAG, "Unable to dispatch event " + eventPhase, exception);
                        }
                    }
                });
            }
            @Override
            public void onAdRewarded(String placementId) {
                if (luaListener == CoronaLua.REFNIL) return;
                taskDispatcher.send(new CoronaRuntimeTask() {
                    @Override
                    public void executeUsing(CoronaRuntime coronaRuntime) {
                        final String eventPhase = AD_REWARD_PHASE;
                        try {
                            final LuaState asyncLuaState = createBaseEvent(coronaRuntime, eventPhase, false);
                            asyncLuaState.pushString(placementId);
                            asyncLuaState.setField(-2, AD_PLACEMENT_ID_KEY);

                            CoronaLua.dispatchEvent(asyncLuaState, luaListener, 0);
                        } catch (Exception exception) {
                            Log.e(TAG, "Unable to dispatch event " + eventPhase, exception);
                        }
                    }
                });
            }
            @Override
            public void onAdLeftApplication(String placementId) {
                //not used
            }
            @Override
            public void onAdViewed(String placementId) {
                //not used
            }
            @Override
            public void onError(final String placementId, final VungleException error) {
                if (luaListener == CoronaLua.REFNIL) return;
                taskDispatcher.send(new CoronaRuntimeTask() {
                    @Override
                    public void executeUsing(CoronaRuntime coronaRuntime) {
                        final String eventPhase = AD_FAILED_PHASE;
                        try {
                            final LuaState asyncLuaState = createBaseEvent(coronaRuntime, eventPhase, true);
                            asyncLuaState.pushString(placementId);
                            asyncLuaState.setField(-2, AD_PLACEMENT_ID_KEY);
                            
                            asyncLuaState.pushString((error != null)?error.getLocalizedMessage():"");
                            asyncLuaState.setField(-2, AD_RESPONSE_KEY);
                            CoronaLua.dispatchEvent(asyncLuaState, luaListener, 0);
                        } catch (Exception exception) {
                            Log.e(TAG, "Unable to dispatch event " + eventPhase, exception);
                        }
                    }
                });
            }
        });
        return 0;
    }

    private class ClearCacheWrapper implements NamedJavaFunction {
        ClearCacheWrapper() {}
        @Override
        public String getName() {
            return CLEAR_CACHE_METHOD;
        }
        @Override
        public int invoke(LuaState luaState) {
            return 0;
        }
    }
    
    private class ClearSleepWrapper implements NamedJavaFunction {
        ClearSleepWrapper() {}
        @Override
        public String getName() {
            return CLEAR_SLEEP_METHOD;
        }
        @Override
        public int invoke(LuaState luaState) {
            return 0;
        }
    }
    
    private class EnableLoggingWrapper implements NamedJavaFunction {
        EnableLoggingWrapper() {}
        @Override
        public String getName() {
            return ENABLE_LOGGING_METHOD;
        }
        @Override
        public int invoke(LuaState luaState) {
            return 0;
        }
    }
    
    private class SubscribeHBWrapper implements NamedJavaFunction {
        SubscribeHBWrapper() {}
        @Override
        public String getName() {
            return SUBSCRIBE_HB_METHOD;
        }
        @Override
        public int invoke(LuaState luaState) {
            return 0;
        }
    }

	// CoronaRuntimeListener
	@Override
	public void onLoaded(CoronaRuntime coronaRuntime) {
		if (!isLuaStateValid()) {
			Log.d(TAG, "onLoaded(): refreshing task dispatcher");
			taskDispatcher = new CoronaRuntimeTaskDispatcher(coronaRuntime.getLuaState());
		}
	}

	// CoronaRuntimeListener
	@Override
	public void onStarted(CoronaRuntime coronaRuntime) {
	}

	// CoronaRuntimeListener
	@Override
	public void onResumed(CoronaRuntime coronaRuntime) {
		if (!isLuaStateValid()) {
			Log.d(TAG, "onResumed(): refreshing task dispatcher");
			taskDispatcher = new CoronaRuntimeTaskDispatcher(coronaRuntime.getLuaState());
		}
//		vunglePub.onResume();
	}

	/**
	 * Returns <code>true</code> if the cached Lua runtime state is valid; otherwise, 
	 * returns <code>false</code>.
	 * 
	 * @return <code>true</code> if the cached Lua runtime state is valid; otherwise, 
	 * returns <code>false</code>.
	 */
	boolean isLuaStateValid() {
		return applicationId != null && taskDispatcher != null && taskDispatcher.isRuntimeAvailable();
	}

	// CoronaRuntimeListener
	@Override
	public void onSuspended(CoronaRuntime coronaRuntime) {
		//vunglePub.onPause();
	}

	// CoronaRuntimeListener
	@Override
	public void onExiting(CoronaRuntime coronaRuntime) {
		Log.d(TAG, "onExiting(): invalidating Lua state");
		final LuaState luaState = coronaRuntime.getLuaState();
		CoronaLua.deleteRef(luaState, luaListener);
		luaListener = CoronaLua.REFNIL;
		taskDispatcher = null;
	}
}
