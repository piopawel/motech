package org.motechproject.server.messagecampaign.scheduler;

import org.joda.time.Days;
import org.joda.time.LocalDate;
import org.motechproject.scheduler.MotechSchedulerService;
import org.motechproject.server.messagecampaign.EventKeys;
import org.motechproject.server.messagecampaign.contract.CampaignRequest;
import org.motechproject.server.messagecampaign.domain.campaign.RepeatingCampaign;
import org.motechproject.server.messagecampaign.domain.message.RepeatingCampaignMessage;
import org.motechproject.valueobjects.WallTime;
import org.motechproject.valueobjects.factory.WallTimeFactory;

import java.util.Map;

public class RepeatingProgramScheduler extends MessageCampaignScheduler<RepeatingCampaignMessage, RepeatingCampaign> {

    public RepeatingProgramScheduler(MotechSchedulerService schedulerService, CampaignRequest enrollRequest, RepeatingCampaign campaign) {
        super(schedulerService, enrollRequest, campaign);
    }

    @Override
    protected void scheduleJobFor(RepeatingCampaignMessage message) {
        LocalDate startDate = referenceDate();
        WallTime duration = WallTimeFactory.create(campaign.maxDuration());
        LocalDate endDate = startDate.plusDays(duration.inDays());

        long repeatInterval = Days.days(message.repeatIntervalForSchedule()).toStandardSeconds().getSeconds() * 1000L;
        Map<String, Object> paramss = jobParams(message.messageKey());
        paramss.put(EventKeys.REPEATING_START_OFFSET, campaignRequest.startOffset());
        scheduleRepeatingJob(startDate, endDate, repeatInterval, paramss);
    }
}
