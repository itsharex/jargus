package com.qqmu.jargus.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.qqmu.jargus.dto.Result;
import com.qqmu.jargus.security.RequiresRole;
import com.qqmu.jargus.service.update.UpdateService;

import lombok.RequiredArgsConstructor;

/**
 * 一键更新。方法名启发式会把 getStatus 按 get 前缀判为 VIEWER，故类级显式锁 ADMIN，
 * 与「仅管理员可更新 / 看更新进度」的预期一致。
 */
@RestController
@RequestMapping("/api/system/update")
@RequiredArgsConstructor
@RequiresRole("ADMIN")
public class SystemUpdateController {

    private final UpdateService updateService;

    /** 进度轮询（前端在触发后每 1-2s 拉一次）。 */
    @GetMapping("/status")
    public Result<UpdateService.Progress> getStatus() {
        return Result.success(updateService.status());
    }

    /** 触发一键更新：前置校验在前台完成（不满足直接 400/500 报错），下载在后台跑。 */
    @PostMapping
    public Result<Map<String, Object>> trigger() {
        try {
            updateService.startUpdate();
            return Result.success(Map.of("started", true));
        } catch (IllegalStateException e) {
            // 前置校验失败（非 admin 由切面拦、无更新源、非 jar 部署等）→ 把原因回给前端
            return Result.error(400, e.getMessage());
        }
    }
}
