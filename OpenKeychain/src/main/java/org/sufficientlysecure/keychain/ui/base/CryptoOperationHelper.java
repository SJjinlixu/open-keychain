/*
 * Copyright (C) 2017 Schürmann & Breitmoser GbR
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sufficientlysecure.keychain.ui.base;


import java.util.Date;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;

import org.sufficientlysecure.keychain.operations.results.InputPendingResult;
import org.sufficientlysecure.keychain.operations.results.OperationResult;
import org.sufficientlysecure.keychain.pgp.Progressable;
import org.sufficientlysecure.keychain.service.KeychainService;
import org.sufficientlysecure.keychain.service.KeychainService.OperationCallback;
import org.sufficientlysecure.keychain.service.ServiceProgressHandler;
import org.sufficientlysecure.keychain.service.ServiceProgressHandler.MessageStatus;
import org.sufficientlysecure.keychain.service.input.CryptoInputParcel;
import org.sufficientlysecure.keychain.service.input.RequiredInputParcel;
import org.sufficientlysecure.keychain.ui.OrbotRequiredDialogActivity;
import org.sufficientlysecure.keychain.ui.PassphraseDialogActivity;
import org.sufficientlysecure.keychain.ui.RetryUploadDialogActivity;
import org.sufficientlysecure.keychain.ui.SecurityTokenOperationActivity;
import org.sufficientlysecure.keychain.ui.dialog.ProgressDialogFragment;
import timber.log.Timber;


/**
 * Designed to be integrated into activities or fragments used for CryptoOperations.
 * Encapsulates the execution of a crypto operation and handling of input pending cases.s
 *
 * @param <T> The type of input parcel sent to the operation
 * @param <S> The type of result returned by the operation
 */
public class CryptoOperationHelper<T extends Parcelable, S extends OperationResult> {

    private long operationStartTime;

    public interface Callback<T extends Parcelable, S extends OperationResult> {
        T createOperationInput();

        void onCryptoOperationSuccess(S result);

        void onCryptoOperationCancelled();

        void onCryptoOperationError(S result);

        boolean onCryptoSetProgress(String msg, int progress, int max);
    }

    public static abstract class AbstractCallback<T extends Parcelable, S extends OperationResult>
            implements Callback<T,S> {
        @Override
        public void onCryptoOperationCancelled() {
            throw new UnsupportedOperationException("Unexpectedly cancelled operation!!");
        }

        @Override
        public boolean onCryptoSetProgress(String msg, int progress, int max) {
            return false;
        }
    }

    // request codes from CryptoOperationHelper are created essentially
    // a static property, used to identify requestCodes meant for this
    // particular helper. a request code looks as follows:
    // (id << 9) + (1<<8) + REQUEST_CODE_X
    // that is, starting from LSB, there are 8 bits request code, 1
    // fixed bit set, then 7 bit helper-id code. the first two
    // summands are stored in the mHelperId for easy operation.
    private final int mHelperId;
    // bitmask for helperId is everything except the least 8 bits
    public static final int HELPER_ID_BITMASK = ~0xff;

    public static final int REQUEST_CODE_PASSPHRASE = 1;
    public static final int REQUEST_CODE_NFC = 2;
    public static final int REQUEST_CODE_ENABLE_ORBOT = 3;
    public static final int REQUEST_CODE_RETRY_UPLOAD = 4;

    private Integer mProgressMessageResource;
    private boolean mCancellable = false;
    private Long minimumOperationDelay;

    private FragmentActivity mActivity;
    private Fragment mFragment;
    private Callback<T, S> mCallback;

    private boolean mUseFragment; // short hand for mActivity == null

    /**
     * If OperationHelper is being integrated into an activity
     */
    public CryptoOperationHelper(int id, FragmentActivity activity, Callback<T, S> callback,
            Integer progressMessageString) {
        mHelperId = (id << 9) + (1<<8);
        mActivity = activity;
        mUseFragment = false;
        mCallback = callback;
        mProgressMessageResource = progressMessageString;
    }

    /**
     * if OperationHelper is being integrated into a fragment
     */
    public CryptoOperationHelper(int id, Fragment fragment, Callback<T, S> callback, Integer progressMessageString) {
        mHelperId = (id << 9) + (1<<8);
        mFragment = fragment;
        mUseFragment = true;
        mProgressMessageResource = progressMessageString;
        mCallback = callback;
    }

    public void setProgressMessageResource(int id) {
        mProgressMessageResource = id;
    }

    public void setOperationMinimumDelay(Long delay) {
        this.minimumOperationDelay = delay;
    }

    public void setProgressCancellable(boolean cancellable) {
        mCancellable = cancellable;
    }

    private void initiateInputActivity(RequiredInputParcel requiredInput,
                                       CryptoInputParcel cryptoInputParcel) {

        Activity activity = mUseFragment ? mFragment.getActivity() : mActivity;

        switch (requiredInput.mType) {
            // always use CryptoOperationHelper.startActivityForResult!
            case SECURITY_TOKEN_MOVE_KEY_TO_CARD:
            case SECURITY_TOKEN_DECRYPT:
            case SECURITY_TOKEN_SIGN: {
                Intent intent = new Intent(activity, SecurityTokenOperationActivity.class);
                intent.putExtra(SecurityTokenOperationActivity.EXTRA_REQUIRED_INPUT, requiredInput);
                intent.putExtra(SecurityTokenOperationActivity.EXTRA_CRYPTO_INPUT, cryptoInputParcel);
                startActivityForResult(intent, REQUEST_CODE_NFC);
                return;
            }

            case PASSPHRASE:
            case PASSPHRASE_SYMMETRIC:
            case BACKUP_CODE:
            case NUMERIC_9X4:
            case NUMERIC_9X4_AUTOCRYPT: {
                Intent intent = new Intent(activity, PassphraseDialogActivity.class);
                intent.putExtra(PassphraseDialogActivity.EXTRA_REQUIRED_INPUT, requiredInput);
                intent.putExtra(PassphraseDialogActivity.EXTRA_CRYPTO_INPUT, cryptoInputParcel);
                startActivityForResult(intent, REQUEST_CODE_PASSPHRASE);
                return;
            }

            case ENABLE_ORBOT: {
                Intent intent = new Intent(activity, OrbotRequiredDialogActivity.class);
                intent.putExtra(OrbotRequiredDialogActivity.EXTRA_CRYPTO_INPUT, cryptoInputParcel);
                startActivityForResult(intent, REQUEST_CODE_ENABLE_ORBOT);
                return;
            }

            case UPLOAD_FAIL_RETRY: {
                Intent intent = new Intent(activity, RetryUploadDialogActivity.class);
                intent.putExtra(RetryUploadDialogActivity.EXTRA_CRYPTO_INPUT, cryptoInputParcel);
                startActivityForResult(intent, REQUEST_CODE_RETRY_UPLOAD);
                return;
            }

            default: {
                throw new RuntimeException("Unhandled pending result!");
            }
        }
    }

    protected void startActivityForResult(Intent intent, int requestCode) {
        if (mUseFragment) {
            mFragment.startActivityForResult(intent, mHelperId + requestCode);
        } else {
            mActivity.startActivityForResult(intent, mHelperId + requestCode);
        }
    }

    /**
     * Attempts the result of an activity started by this helper. Returns true if requestCode is
     * recognized, false otherwise.
     * @return true if requestCode was recognized, false otherwise
     */
    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        Timber.d("received activity result in OperationHelper");

        if ((requestCode & HELPER_ID_BITMASK) != mHelperId) {
            // this wasn't meant for us to handle
            return false;
        }
        Timber.d("handling activity result in OperationHelper");
        // filter out mHelperId from requestCode
        requestCode ^= mHelperId;

        if (resultCode == Activity.RESULT_CANCELED) {
            mCallback.onCryptoOperationCancelled();
            return true;
        }

        switch (requestCode) {
            case REQUEST_CODE_PASSPHRASE: {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    CryptoInputParcel cryptoInput =
                            data.getParcelableExtra(PassphraseDialogActivity.RESULT_CRYPTO_INPUT);
                    cryptoOperation(cryptoInput);
                }
                break;
            }

            case REQUEST_CODE_NFC: {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    CryptoInputParcel cryptoInput =
                            data.getParcelableExtra(SecurityTokenOperationActivity.RESULT_CRYPTO_INPUT);
                    cryptoOperation(cryptoInput);
                }
                break;
            }

            case REQUEST_CODE_ENABLE_ORBOT: {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    CryptoInputParcel cryptoInput =
                            data.getParcelableExtra(
                                    OrbotRequiredDialogActivity.RESULT_CRYPTO_INPUT);
                    cryptoOperation(cryptoInput);
                }
                break;
            }

            case REQUEST_CODE_RETRY_UPLOAD: {
                if (resultCode == Activity.RESULT_OK) {
                    CryptoInputParcel cryptoInput =
                            data.getParcelableExtra(
                                    RetryUploadDialogActivity.RESULT_CRYPTO_INPUT);
                    cryptoOperation(cryptoInput);
                }
                break;
            }
        }

        return true;
    }

    protected void dismissProgress() {
        FragmentManager fragmentManager =
                mUseFragment ? mFragment.getFragmentManager() :
                        mActivity.getSupportFragmentManager();

        if (fragmentManager == null) { // the fragment holding us has died
            // fragmentManager was null when used with DialogFragments. (they close on click?)
            return;
        }

        ProgressDialogFragment progressDialogFragment =
                (ProgressDialogFragment) fragmentManager.findFragmentByTag(
                        ServiceProgressHandler.TAG_PROGRESS_DIALOG);

        if (progressDialogFragment == null) {
            return;
        }

        progressDialogFragment.dismissAllowingStateLoss();

    }

    public void cryptoOperation(final CryptoInputParcel cryptoInput) {

        FragmentActivity activity = mUseFragment ? mFragment.getActivity() : mActivity;

        T operationInput = mCallback.createOperationInput();
        if (operationInput == null) {
            return;
        }

        ServiceProgressHandler saveHandler = new ServiceProgressHandler(activity) {
            @Override
            public void handleMessage(Message message) {
                // handle messages by standard KeychainIntentServiceHandler first
                super.handleMessage(message);

                if (message.arg1 == MessageStatus.OKAY.ordinal()) {

                    // get returned data bundle
                    Bundle returnData = message.getData();
                    if (returnData == null) {
                        return;
                    }

                    final OperationResult result =
                            returnData.getParcelable(OperationResult.EXTRA_RESULT);

                    onHandleResult(result);
                }
            }

            @Override
            protected void onSetProgress(String msg, int progress, int max) {
                // allow handling of progress in fragment, or delegate upwards
                if (!mCallback.onCryptoSetProgress(msg, progress, max)) {
                    super.onSetProgress(msg, progress, max);
                }
            }
        };

        Messenger messenger = new Messenger(saveHandler);
        Communicator communicator = new Communicator(messenger);

        Progressable progressable = new Progressable() {
            @Override
            public void setProgress(String message, int progress, int max) {
                Timber.d("Send message by setProgress with progress=" + progress + ", max=" + max);

                Bundle data = new Bundle();
                if (message != null) {
                    data.putString(ServiceProgressHandler.DATA_MESSAGE, message);
                }
                data.putInt(ServiceProgressHandler.DATA_PROGRESS, progress);
                data.putInt(ServiceProgressHandler.DATA_PROGRESS_MAX, max);

                communicator.sendMessageToHandler(MessageStatus.UPDATE_PROGRESS, data);
            }

            @Override
            public void setProgress(int resourceId, int progress, int max) {
                setProgress(activity.getString(resourceId), progress, max);
            }

            @Override
            public void setProgress(int progress, int max) {
                setProgress(null, progress, max);
            }

            @Override
            public void setPreventCancel() {
                communicator.sendMessageToHandler(MessageStatus.PREVENT_CANCEL, (Bundle) null);
            }
        };

        if (mProgressMessageResource != null) {
            saveHandler.showProgressDialog(
                    activity.getString(mProgressMessageResource),
                    ProgressDialog.STYLE_HORIZONTAL, mCancellable);
        }

        KeychainService keychainService = KeychainService.getInstance(activity);
        keychainService.startOperationInBackground(operationInput, cryptoInput, progressable, communicator);
    }

    public void cryptoOperation() {
        operationStartTime = SystemClock.elapsedRealtime();
        cryptoOperation(CryptoInputParcel.createCryptoInputParcel(new Date()));
    }

    private void onHandleResult(final OperationResult result) {
        Timber.d("Handling result in OperationHelper success: " + result.success());

        if (result instanceof InputPendingResult) {
            InputPendingResult pendingResult = (InputPendingResult) result;
            if (pendingResult.isPending()) {
                RequiredInputParcel requiredInput = pendingResult.getRequiredInputParcel();
                initiateInputActivity(requiredInput, pendingResult.mCryptoInputParcel);
                return;
            }
        }

        dismissProgress();

        long elapsedTime = SystemClock.elapsedRealtime() - operationStartTime;
        if (minimumOperationDelay == null || elapsedTime > minimumOperationDelay) {
            returnResultToCallback(result);
            return;
        }

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                returnResultToCallback(result);
            }
        }, minimumOperationDelay - elapsedTime);
    }

    private void returnResultToCallback(OperationResult result) {
        try {
            if (result.success()) {
                // noinspection unchecked, because type erasure :(
                mCallback.onCryptoOperationSuccess((S) result);
            } else {
                // noinspection unchecked, because type erasure :(
                mCallback.onCryptoOperationError((S) result);
            }
        } catch (ClassCastException e) {
            throw new AssertionError("bad return class ("
                    + result.getClass().getSimpleName() + "), this is a programming error!");
        }
    }

    public static class Communicator implements OperationCallback {
        final Messenger messenger;

        Communicator(Messenger messenger) {
            this.messenger = messenger;
        }

        public void sendMessageToHandler(MessageStatus status, Bundle data) {
            Message msg = Message.obtain();
            assert msg != null;
            msg.arg1 = status.ordinal();
            if (data != null) {
                msg.setData(data);
            }

            try {
                messenger.send(msg);
            } catch (RemoteException e) {
                Timber.w(e, "Exception sending message, Is handler present?");
            } catch (NullPointerException e) {
                Timber.w(e, "Messenger is null!");
            }
        }

        @Override
        public void operationFinished(OperationResult data) {
            Bundle bundle = new Bundle();
            bundle.putParcelable(OperationResult.EXTRA_RESULT, data);
            sendMessageToHandler(MessageStatus.OKAY, bundle);
        }
    }

}