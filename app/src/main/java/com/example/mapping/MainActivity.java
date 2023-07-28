package com.example.mapping;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;

import android.app.AutomaticZenRule;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;

import com.aldebaran.qi.Consumer;
import com.aldebaran.qi.Future;
import com.aldebaran.qi.Promise;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.QiSDK;
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks;
import com.aldebaran.qi.sdk.builder.LocalizeAndMapBuilder;
import com.aldebaran.qi.sdk.design.activity.RobotActivity;
import com.aldebaran.qi.sdk.object.actuation.ExplorationMap;
import com.aldebaran.qi.sdk.object.actuation.LocalizationStatus;
import com.aldebaran.qi.sdk.object.actuation.LocalizeAndMap;
import com.aldebaran.qi.sdk.util.FutureUtils;
import com.example.mapping.databinding.ActivityMainBinding;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

public class MainActivity extends RobotActivity implements RobotLifecycleCallbacks {


    ActivityMainBinding design;

    // The QiContext provided by the QiSDK.
    private QiContext qiContext;
    // The initial ExplorationMap.
    private ExplorationMap initialExplorationMap = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Register the RobotLifecycleCallbacks to this Activity.
        QiSDK.register(this, this);
        design=ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(design.getRoot());

        // Set the button onClick listener.
        design.startMappingButton.setOnClickListener(ignored -> {
            // Check that the Activity owns the focus.
            if(qiContext == null) return;
            // Start the mapping step.
            startMappingStep(qiContext);
        });

        design.extendMapButton.setOnClickListener(ignored -> {
            // Check that an initial map is available.
            if (initialExplorationMap == null) return;
            // Check that the Activity owns the focus.
            if (qiContext == null) return;
            // Start the map extension step.
            startMapExtensionStep(initialExplorationMap, qiContext);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reset UI and variables state.
        design.startMappingButton.setEnabled(false);
        design.extendMapButton.setEnabled(false);
        initialExplorationMap = null;
        design.mapImageView.setImageBitmap(null);
    }
    @Override
    protected void onDestroy() {
        // Unregister the RobotLifecycleCallbacks for this Activity.
        QiSDK.unregister(this, this);
        super.onDestroy();
    }

    @Override
    public void onRobotFocusGained(QiContext qiContext) {
        // The robot focus is gained.
        // Store the provided QiContext.
        this.qiContext = qiContext;
        // Enable "start mapping" button.
        runOnUiThread(() -> design.startMappingButton.setEnabled(true));
    }

    @Override
    public void onRobotFocusLost() {
        // The robot focus is lost.
        // Remove the QiContext.
        this.qiContext = null;
    }

    @Override
    public void onRobotFocusRefused(String reason) {
        // The robot focus is refused.
    }
    private Future<ExplorationMap> mapSurroundings(QiContext qiContext) {
        // Create a Promise to set the operation state later.
        Promise<ExplorationMap> promise = new Promise<>();
        // If something tries to cancel the associated Future, do cancel it.
        promise.setOnCancel(ignored -> {
            if (!promise.getFuture().isDone()) {
                promise.setCancelled();
            }
        });

        // Create a LocalizeAndMap, run it, and keep the Future.
        Future<Void> localizeAndMapFuture = LocalizeAndMapBuilder.with(qiContext)
                .buildAsync()
                .andThenCompose(localizeAndMap -> {
                    // Add an OnStatusChangedListener to know when the robot is localized.
                    localizeAndMap.addOnStatusChangedListener(status -> {
                        if (status == LocalizationStatus.LOCALIZED) {
                            // Retrieve the map.
                            ExplorationMap explorationMap = localizeAndMap.dumpMap();
                            // Set the Promise state in success, with the ExplorationMap.
                            if (!promise.getFuture().isDone()) {
                                promise.setValue(explorationMap);
                            }
                        }
                    });

                    // Run the LocalizeAndMap.
                    return localizeAndMap.async().run().thenConsume(future -> {
                        // Remove the OnStatusChangedListener.
                        localizeAndMap.removeAllOnStatusChangedListeners();
                        // In case of error, forward it to the Promise.
                        if (future.hasError() && !promise.getFuture().isDone()) {
                            promise.setError(future.getErrorMessage());
                        }
                    });
                });

        // Return the Future associated to the Promise.
        return promise.getFuture().thenCompose(future -> {
            // Stop the LocalizeAndMap.
            localizeAndMapFuture.cancel(true);
            return future;
        });
    }
    private Bitmap mapToBitmap(ExplorationMap explorationMap) {
        // Get the ByteBuffer containing the map graphical representation.
        ByteBuffer byteBuffer = explorationMap.getTopGraphicalRepresentation().getImage().getData();
        byteBuffer.rewind();
        // Get the buffer size.
        int size = byteBuffer.remaining();
        // Transform the buffer to a ByteArray.
        byte[] byteArray = new byte[size];
        byteBuffer.get(byteArray);
        // Transform the ByteArray to a Bitmap.
        return BitmapFactory.decodeByteArray(byteArray, 0, size);
    }
    private void displayMap(Bitmap bitmap) {
        // Set the ImageView bitmap.
        design.mapImageView.setImageBitmap(bitmap);
    }

    private void startMappingStep(QiContext qiContext) {
        // Disable "start mapping" button.

        design.startMappingButton.setEnabled(false);
        // Map the surroundings and get the map.
        mapSurroundings(qiContext).thenConsume(future -> {
            if (future.isSuccess()) {
                ExplorationMap explorationMap = future.get();
                // Store the initial map.
                this.initialExplorationMap = explorationMap;
                // Convert the map to a bitmap.
                Bitmap bitmap = mapToBitmap(explorationMap);
                // Display the bitmap and enable "extend map" button.
                runOnUiThread(() -> {
                    if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                        displayMap(bitmap);
                        design.extendMapButton.setEnabled(true);
                    }
                });
            } else {
                // If the operation is not a success, re-enable "start mapping" button.
                runOnUiThread(() -> {
                    if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                        design.startMappingButton.setEnabled(true);
                    }
                });
            }
        });
    }

    private Future<Void> publishExplorationMap(LocalizeAndMap localizeAndMap, Consumer<ExplorationMap> updatedMapCallback) {
        // Retrieve the map.
        return localizeAndMap.async().dumpMap().andThenCompose(explorationMap -> {
            // Call the callback with the map.
            updatedMapCallback.consume(explorationMap);
            // Wait for 2 seconds.
            return FutureUtils.wait(2L, TimeUnit.SECONDS);
        }).andThenCompose(ignored -> {
            // Call the method recursively.
            return publishExplorationMap(localizeAndMap, updatedMapCallback);
        });
    }

    private Future<Void> extendMap(ExplorationMap explorationMap, QiContext qiContext, Consumer<ExplorationMap> updatedMapCallback) {
        // Create a Promise to set the operation state later.
        Promise<Void> promise = new Promise<>();
        // If something tries to cancel the associated Future, do cancel it.
        promise.setOnCancel(ignored -> {
            if (!promise.getFuture().isDone()) {
                promise.setCancelled();
            }
        });

        // Create a LocalizeAndMap with the initial map, run it, and keep the Future.
        Future<Void> localizeAndMapFuture = LocalizeAndMapBuilder.with(qiContext)
                .withMap(explorationMap)
                .buildAsync()
                .andThenCompose(localizeAndMap -> {
                    // Create a Future for map notification.
                    final Future<Void>[] publishExplorationMapFuture = new Future[]{null};

                    // Add an OnStatusChangedListener to know when the robot is localized.
                    localizeAndMap.addOnStatusChangedListener(status -> {
                        if (status == LocalizationStatus.LOCALIZED) {
                            // Start the map notification process.
                            publishExplorationMapFuture[0] = publishExplorationMap(localizeAndMap, updatedMapCallback);
                        }
                    });

                    // Run the LocalizeAndMap.
                    return localizeAndMap.async().run().thenConsume(future -> {
                        // Remove the OnStatusChangedListener.
                        localizeAndMap.removeAllOnStatusChangedListeners();
                        // Stop the map notification process.
                        if (publishExplorationMapFuture[0] != null) {
                            publishExplorationMapFuture[0].cancel(true);
                        }

                        // In case of error, forward it to the Promise.
                        if (future.hasError() && !promise.getFuture().isDone()) {
                            promise.setError(future.getErrorMessage());
                        }
                    });
                });

        // Return the Future associated to the Promise.
        return promise.getFuture().thenCompose(future -> {
            // Stop the LocalizeAndMap.
            localizeAndMapFuture.cancel(true);
            return future;
        });
    }

    private void startMapExtensionStep(ExplorationMap initialExplorationMap, QiContext qiContext) {
        // Disable "extend map" button.
        design.extendMapButton.setEnabled(false);
        // Start the map extension and notify each time the map is updated.
        extendMap(initialExplorationMap, qiContext, updatedMap -> {
            // Convert the map to a bitmap.
            Bitmap updatedBitmap = mapToBitmap(updatedMap);
            // Display the bitmap.
            runOnUiThread(() -> {
                if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                    displayMap(updatedBitmap);
                }
            });
        }).thenConsume(future -> {
            // If the operation is not a success, re-enable "extend map" button.
            if (!future.isSuccess()) {
                runOnUiThread(() -> {
                    if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                        design.extendMapButton.setEnabled(true);
                    }
                });
            }
        });
    }





}
