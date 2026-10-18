package ru.wohlsoft.moondust;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/**
 * Receives PackageInstaller session results for {@link Updater}. Not exported: only the PendingIntent that
 * Updater hands to PackageInstaller.Session.commit() can reach it.
 */
public class UpdateInstallReceiver extends BroadcastReceiver
{
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context ctx, Intent in)
    {
        if(in == null)
            return;
        int status = in.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String msg = in.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        if(status == PackageInstaller.STATUS_PENDING_USER_ACTION)
        {
            // The platform needs the user's one-tap confirmation: show its screen
            try
            {
                Intent confirm = in.getParcelableExtra(Intent.EXTRA_INTENT);
                if(confirm != null)
                {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(confirm);
                }
            }
            catch(Exception e)
            {
                Updater.deliverResult(ctx, false, "Could not open the Android install screen: " + e.getMessage());
            }
            return;
        }

        if(status == PackageInstaller.STATUS_SUCCESS)
            Updater.deliverResult(ctx, true, "Update installed.");
        else
            Updater.deliverResult(ctx, false, Updater.describeFailure(status, msg));
    }
}
