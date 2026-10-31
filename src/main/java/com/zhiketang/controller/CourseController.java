package com.zhiketang.controller;

import com.zhiketang.common.BaseController;
import com.zhiketang.common.BizException;
import com.zhiketang.common.R;
import com.zhiketang.common.SessionUser;
import com.zhiketang.mapper.CourseMapper;
import com.zhiketang.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 课程与基础数据接口
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CourseController extends BaseController {

    private final CourseMapper courseMapper;
    private final UserMapper userMapper;

    @GetMapping("/courses")
    public R<List<Map<String, Object>>> myCourses() {
        SessionUser u = cur();
        if (u.isStudent()) {
            return R.ok(courseMapper.listByStudent(u.getUserId()));
        }
        return R.ok(courseMapper.listByTeacher(u.getUserId()));
    }

    @GetMapping("/courses/{cid}")
    public R<Map<String, Object>> detail(@PathVariable int cid) {
        Map<String, Object> c = courseMapper.findById(cid);
        if (c == null) return R.fail("课程不存在");
        return R.ok(c);
    }

    @GetMapping("/courses/{cid}/students")
    public R<List<Map<String, Object>>> students(@PathVariable int cid) {
        return R.ok(courseMapper.courseStudents(cid));
    }

    @GetMapping("/courses/{cid}/knowledge-points")
    public R<List<Map<String, Object>>> knowledgePoints(@PathVariable int cid) {
        return R.ok(userMapper.listKnowledgePoints(cid));
    }

    @GetMapping("/classes")
    public R<List<Map<String, Object>>> classes() {
        return R.ok(userMapper.listClasses());
    }

    /** 获取当前用户可访问的课程 ID（用于页面级课程切换） */
    @GetMapping("/courses/ids")
    public R<List<Integer>> ids() {
        SessionUser u = cur();
        List<Map<String, Object>> cs = u.isStudent()
                ? courseMapper.listByStudent(u.getUserId())
                : courseMapper.listByTeacher(u.getUserId());
        return R.ok(cs.stream().map(c -> ((Number) c.get("course_id")).intValue()).toList());
    }

    @GetMapping("/ping")
    public R<String> ping() {
        if (cur() == null) throw new BizException("未登录", 401);
        return R.ok("pong");
    }
}
