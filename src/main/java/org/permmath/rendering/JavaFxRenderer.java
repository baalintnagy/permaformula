package org.permmath.rendering;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import java.nio.charset.StandardCharsets;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;


import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;


final class JavaFxRenderer {
    private static boolean started;

    private static synchronized void start() {
        if (!started) {
            Platform.startup(() -> Platform.setImplicitExit(false));
            started = true;
        }
    }

    static byte[] render(String url, int width, java.time.Duration timeout) throws Exception {
        start();
        var result = new CompletableFuture<byte[]>();
        Platform.runLater(() -> {
            var view = new WebView();
            view.setPrefSize(width, 600);
            var scene = new Scene(view, width, 600);
            var stage = new Stage(StageStyle.UNDECORATED);
            stage.setOpacity(0);
            stage.setScene(scene);
            stage.show();
            var engine = view.getEngine();
            var timer = new PauseTransition(Duration.millis(timeout.toMillis()));
            timer.setOnFinished(_ -> result.completeExceptionally(
                    new IllegalStateException("JavaFX rendering timed out")));
            timer.play();
            result.whenComplete((_, _) -> Platform.runLater(() -> {
                timer.stop();
                engine.load(null);
                stage.close();
            }));
            engine.getLoadWorker().stateProperty().addListener((_, _, state) -> {
                if (state == Worker.State.FAILED) {
                    result.completeExceptionally(engine.getLoadWorker().getException());
                } else if (state == Worker.State.SUCCEEDED) {
                    awaitReady(view, scene, width, result);
                }
            });
            engine.load(url);
        });
        return result.get(timeout.toMillis() + 1000, TimeUnit.MILLISECONDS);
    }

    private static void awaitReady(WebView view, Scene scene, int width, CompletableFuture<byte[]> result) {
        if (result.isDone()) return;
        try {
            var state = view.getEngine().executeScript("window.renderState");
            if ("error".equals(state)) {
                result.completeExceptionally(new IllegalArgumentException(
                        "Typesetting failed: " + view.getEngine().executeScript("window.renderError")));
            } else if ("ready".equals(state)) {
                result.complete(((String) view.getEngine().executeScript("window.renderSvg")).getBytes(StandardCharsets.UTF_8));
            } else {
                var pause = new PauseTransition(Duration.millis(25));
                pause.setOnFinished(_ -> awaitReady(view, scene, width, result));
                pause.play();
            }
        } catch (Exception e) { result.completeExceptionally(e); }
    }
}
