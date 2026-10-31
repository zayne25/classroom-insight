package com.zhiketang.config;

import com.zhiketang.common.SessionUser;
import com.zhiketang.interceptor.LoginInterceptor;
import com.zhiketang.service.SysConfigService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：注册登录拦截器 + 向所有模板注入公共变量（统一布局需要）
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private LoginInterceptor loginInterceptor;

    /**
     * 注册登录拦截器。
     * 注意：classpath:/static/ 下的资源是映射到站点根目录的（/css/**、/js/**、/vendor/**），
     * 必须一并排除，否则静态资源会被重定向到登录页，导致样式与脚本全部失效。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/login",
                        "/api/auth/**",
                        "/css/**",
                        "/js/**",
                        "/vendor/**",
                        "/images/**",
                        "/img/**",
                        "/webjars/**",
                        "/static/**",
                        "/error",
                        "/favicon.ico"
                );
    }

    /**
     * 向所有页面注入 currentUser（供 base.html 顶栏使用）
     */
    @ControllerAdvice
    public static class GlobalModelAttr {

        /** 系统名称取自系统配置（app.name），管理员修改后全站顶栏实时生效 */
        @Autowired
        private SysConfigService sysConfig;

        @ModelAttribute("currentUser")
        public SessionUser currentUser(HttpSession session) {
            Object o = session.getAttribute(LoginInterceptor.SESSION_KEY);
            return o == null ? null : (SessionUser) o;
        }

        @ModelAttribute("appName")
        public String appName() {
            try {
                String n = sysConfig.str("app.name");
                return n == null || n.isBlank() ? "智课堂" : n;
            } catch (Exception e) {
                return "智课堂";
            }
        }
    }
}
