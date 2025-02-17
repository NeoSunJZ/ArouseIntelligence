package com.oriole.ocean.controller;

import com.alibaba.fastjson.JSONObject;
import com.oriole.ocean.common.enumerate.*;
import com.oriole.ocean.common.auth.AuthUser;
import com.oriole.ocean.common.po.mongo.UserBehaviorEntity;
import com.oriole.ocean.common.po.mysql.FileEntity;
import com.oriole.ocean.common.service.UserBehaviorService;
import com.oriole.ocean.common.service.UserWalletService;
import com.oriole.ocean.common.tools.JwtUtils;
import com.oriole.ocean.common.vo.AuthUserEntity;
import com.oriole.ocean.common.vo.BusinessException;
import com.oriole.ocean.common.vo.MsgEntity;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.oriole.ocean.service.*;


import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.oriole.ocean.common.enumerate.ResultCode.*;

@RestController
@Slf4j
@RequestMapping("/docFunctionService")
public class DocFunctionController {

    @Autowired
    FileServiceImpl fileService;
    @Autowired
    FileExtraServiceImpl fileExtraService;
    @DubboReference
    UserBehaviorService userBehaviorService;
    @DubboReference
    UserWalletService userWalletService;

    @Value("${auth.download.token.secretkey}")
    public String DOWNLOAD_TOKEN_ENCODED_SECRET_KEY;

    @RequestMapping(value = "/getUserBehaviorByFileID", method = RequestMethod.GET)
    public MsgEntity<List<UserBehaviorEntity>> getUserBehaviorByFileID(@AuthUser String username, @RequestParam Integer fileID) {
        UserBehaviorEntity userBehaviorEntityQuery = new UserBehaviorEntity(fileID, MainType.DOCUMENT, username, null);
        return new MsgEntity<>("SUCCESS", "1", userBehaviorService.findAllBehaviorRecords(userBehaviorEntityQuery));
    }

    //系统在用户阅读一分钟后执行该事件，无需用户操作
    @RequestMapping(value = "/read", method = RequestMethod.GET)
    public MsgEntity<String> readDoc(@AuthUser AuthUserEntity authUserEntity, @RequestParam Integer fileID) {
        UserBehaviorEntity userBehaviorEntity = new UserBehaviorEntity(fileID, MainType.DOCUMENT, authUserEntity.getUsername(), BehaviorType.DO_READ);
        if (userBehaviorService.findBehaviorRecord(userBehaviorEntity) == null) {
            // 数据库阅读量+1
            fileExtraService.docBaseStatisticsInfoChange(fileID, "+1", DocStatisticItemType.READ);
        }
        userBehaviorService.setBehaviorRecord(userBehaviorEntity);
        return new MsgEntity<>(SUCCESS);
    }

    @RequestMapping(value = "/evaluateDoc", method = RequestMethod.GET)
    public MsgEntity<String> evaluate(@AuthUser AuthUserEntity authUser,
                                      @RequestParam Integer fileID,
                                      @RequestParam Boolean isCancel,
                                      @RequestParam Boolean isLike) {
        FileEntity fileEntity = fileService.getFileBaseInfoByFileID(fileID);
        // 检查是否是在评价自己的文章
        if (authUser.isUserOwn(fileEntity.getUploadUsername())) {
            throw new BusinessException(DOCS_NOT_ALLOW_SELF_EVALUATION);
        }
        // 检查并添加用户行为
        UserBehaviorEntity userBehaviorEntity = new UserBehaviorEntity(fileID, MainType.DOCUMENT, authUser.getUsername(), null);
        List<EvaluateType> evaluates = userBehaviorService.checkAndGetUserEvaluateBehavior(userBehaviorEntity, isCancel, isLike);
        userBehaviorService.setUserEvaluateBehavior(userBehaviorEntity, evaluates);
        // 修改数据库
        fileExtraService.evaluateDoc(fileID, evaluates);
        return new MsgEntity<>(SUCCESS);
    }

    @RequestMapping(value = "/score", method = RequestMethod.GET)
    public MsgEntity<String> scoreDoc(@AuthUser AuthUserEntity authUser,
                                      @RequestParam Integer fileID, @RequestParam Double score) {
        UserBehaviorEntity userBehaviorEntity = new UserBehaviorEntity(fileID, MainType.DOCUMENT, authUser.getUsername(), BehaviorType.DO_SCORED);
        if (userBehaviorService.findBehaviorRecord(userBehaviorEntity) == null) {
            FileEntity fileEntity = fileService.getFileBaseInfoByFileID(fileID);
            // 检查是否是在评价自己的文章
            if (authUser.isUserOwn(fileEntity.getUploadUsername())) {
                throw new BusinessException(DOCS_NOT_ALLOW_SELF_EVALUATION);
            }
            // 添加用户行为
            userBehaviorEntity.setExtraInfo(BehaviorExtraInfo.EVALUATE_SCORE, score);
            userBehaviorService.setBehaviorRecord(userBehaviorEntity);

            // 修改数据库
            fileExtraService.docScoreChange(fileID, score, 1);
            return new MsgEntity<>(SUCCESS);
        } else {
            throw new BusinessException(USER_BEHAVIOR_REPEAT);
        }
    }

    @RequestMapping(value = "/getDownloadFileToken", method = RequestMethod.GET)
    public MsgEntity<String> getDownloadFileToken(@AuthUser String username, @RequestParam Integer fileID) throws Exception {
        String token = null;
        FileEntity fileEntity = fileService.getFileBaseInfoByFileID(fileID);
        // 如果曾经下载过，无论何种情况都无需付费
        // 也不计入文件下载量
        UserBehaviorEntity userBehaviorEntity = new UserBehaviorEntity(fileID, MainType.DOCUMENT, username, BehaviorType.DO_DOWNLOAD);
        if (userBehaviorService.findBehaviorRecord(userBehaviorEntity) != null) {
            token = tokenCreator(username, fileEntity.getRealObjectName(), fileEntity.getTitle());
            return new MsgEntity<>("SUCCESS", "2", token);
        }
        //如果是自己的文档，那么不需要付费即可下载
        //如果文档不需要付费，也可以直接下载
        JSONObject extraInfo = new JSONObject();
        extraInfo.put("uploadUsername", fileEntity.getUploadUsername());

        if (fileEntity.getUploadUsername().equals(username) || fileEntity.getPaymentMethod().equals((byte) 1)) {
            extraInfo.put("realPayment", "FreeDownload");
            token = tokenCreator(username, fileEntity.getRealObjectName(), fileEntity.getTitle());
        } else {
            if (userWalletService.isVipAndIsCanDo(username, VipOperationType.DOWNLOAD_DOCS) == VipOperationState.DO
                    && fileEntity.getIsAllowVipfree().equals((byte) 1)) {
                extraInfo.put("realPayment", "VIPFreeDownload");
                token = tokenCreator(username, fileEntity.getRealObjectName(), fileEntity.getTitle());
            } else {
                extraInfo.put("realPayment", "WalletPay");
                //判断文档是否需要付费，怎么付费
                switch (fileEntity.getPaymentMethod()) {
                    case 2:
                        if (userWalletService.changeWalletCoin(username, fileEntity.getPaymentAmount() * -1, "下载文档消耗金币", fileID)) {
                            userWalletService.changeWalletCoin(fileEntity.getUploadUsername(), fileEntity.getPaymentAmount(), "文档被下载获得金币", fileID);
                            token = tokenCreator(username, fileEntity.getRealObjectName(), fileEntity.getTitle());
                        } else {
                            throw new BusinessException("-2", "金币余额不足，无法下载");
                        }
                        break;
                    case 3:
                        if (userWalletService.changeWalletCoin(username, fileEntity.getPaymentAmount() * -1, "下载文档消耗下载券", fileID)) {
                            userWalletService.changeWalletCoin(fileEntity.getUploadUsername(), fileEntity.getPaymentAmount(), "文档被下载获得下载券", fileID);
                            token = tokenCreator(username, fileEntity.getRealObjectName(), fileEntity.getTitle());
                        } else {
                            throw new BusinessException("-2", "下载券余额不足，无法下载");
                        }
                        break;
                    default:
                        break;
                }
            }
            //写入下载行为
            extraInfo.put("paymentMethod", fileEntity.getPaymentMethod());
            extraInfo.put("paymentAmount", fileEntity.getPaymentAmount());
        }
        userBehaviorEntity.setExtraInfo(extraInfo);
        userBehaviorService.setBehaviorRecord(userBehaviorEntity);
        // 增加文件下载量
        fileExtraService.docBaseStatisticsInfoChange(fileID, "+1", DocStatisticItemType.DOWNLOAD);
        return new MsgEntity<>("SUCCESS", "1", token);
    }

    private String tokenCreator(String username, String realObjectName, String title) {
        Map<String, Object> chaim = new HashMap<>();
        chaim.put("username", username);
        chaim.put("realObjectName", realObjectName);
        chaim.put("title", title);
        JwtUtils jwtUtils = new JwtUtils(DOWNLOAD_TOKEN_ENCODED_SECRET_KEY);
        return jwtUtils.encode(username, 60 * 1000, chaim);
    }

}
