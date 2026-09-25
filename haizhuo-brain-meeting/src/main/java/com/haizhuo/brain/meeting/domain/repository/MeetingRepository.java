package com.haizhuo.brain.meeting.domain.repository;
import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.meeting.domain.model.Meeting;
import java.util.Optional;
public interface MeetingRepository { void save(Meeting meeting); Optional<Meeting> findByMeetingNo(TenantId tenantId, String meetingNo); }
