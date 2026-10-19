package com.nemo.OJbackendquestionservice.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.nemo.OJbackendmodel.model.dto.feedback.FeedbackAddRequest;
import com.nemo.OJbackendmodel.model.dto.feedback.FeedbackHandleRequest;
import com.nemo.OJbackendmodel.model.dto.feedback.FeedbackQueryRequest;
import com.nemo.OJbackendmodel.model.entity.Feedback;
import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJbackendmodel.model.vo.FeedbackVO;

public interface FeedbackService extends IService<Feedback> {
    long createFeedback(FeedbackAddRequest request, User user);
    Page<FeedbackVO> listFeedback(FeedbackQueryRequest request, Long forcedUserId);
    FeedbackVO getFeedback(long id, User viewer, boolean adminOnly);
    FeedbackVO handleFeedback(FeedbackHandleRequest request, User admin);
}
