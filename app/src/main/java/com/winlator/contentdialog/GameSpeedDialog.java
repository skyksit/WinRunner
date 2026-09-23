package com.winlator.contentdialog;

import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;

import com.winlator.R;
import com.winlator.XServerDisplayActivity;
import com.winlator.speed.GameSpeed;
import com.winlator.speed.SpeedController;

/**
 * The drawer's way into the game speed, for sessions whose controls layout carries no speed button.
 * The two speed values are global and persisted, as in dsam3; the mode is per-session.
 */
public class GameSpeedDialog extends ContentDialog {
    public GameSpeedDialog(XServerDisplayActivity activity) {
        super(activity, R.layout.game_speed_dialog);
        setTitle(R.string.game_speed);
        setIcon(R.drawable.icon_game_speed);

        final SpeedController speedController = activity.getSpeedController();

        final Spinner sSpeedMode = findViewById(R.id.SSpeedMode);
        sSpeedMode.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, new String[]{
            activity.getString(R.string.normal_speed),
            activity.getString(R.string.fast_forward),
            activity.getString(R.string.slow_motion)
        }));
        sSpeedMode.setSelection(speedController.getMode().ordinal());

        final Spinner sFastForwardSpeed = findViewById(R.id.SFastForwardSpeed);
        sFastForwardSpeed.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, GameSpeed.fastForwardLabels()));
        sFastForwardSpeed.setSelection(GameSpeed.nearestStep(speedController.getFastForwardSpeed()));

        final Spinner sSlowMotionSpeed = findViewById(R.id.SSlowMotionSpeed);
        sSlowMotionSpeed.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, GameSpeed.slowMotionLabels()));
        sSlowMotionSpeed.setSelection(GameSpeed.nearestStep(1.0f / speedController.getSlowMotionSpeed()));

        final CheckBox cbShowSpeedNotification = findViewById(R.id.CBShowSpeedNotification);
        cbShowSpeedNotification.setChecked(speedController.isToastEnabled());

        setOnConfirmCallback(() -> {
            // Speeds first: setMode below then takes effect with the values just chosen.
            speedController.setSpeeds(GameSpeed.STEPS[sFastForwardSpeed.getSelectedItemPosition()],
                    GameSpeed.slowMotionFactor(sSlowMotionSpeed.getSelectedItemPosition()),
                    cbShowSpeedNotification.isChecked());
            speedController.setMode(SpeedController.Mode.values()[sSpeedMode.getSelectedItemPosition()], false);
        });
    }
}
