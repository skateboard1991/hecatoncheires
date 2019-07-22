# 百臂巨人
一款静态代码检测工具，包含阿里java规约检测和lint检测，支持自定义pmd和lint配置，结合git在代码提交时进行增量检测
![image](https://github.com/skateboard1991/hecatoncheires/blob/master/icon.jpeg)

# 原理图
![image](https://github.com/skateboard1991/hecatoncheires/blob/master/flow.png)

#使用
## 1.
在工程文件的gradle中声明插件引用（因为还在审核期，所以暂时无法使用）

```
buildscript {
  repositories {
    maven {
      url "https://plugins.gradle.org/m2/"
    }
  }
  dependencies {
    classpath "com.skateboard.hecatoncheires:hecatoncheires:1.0.0"
  }
}
```

## 2.
在项目module的gradle文件中引用插件
```
apply plugin: 'hecatoncheires'
```

## 3.
在项目module中设置extension

```
hecatoncheires {
    enable = true //是否开启提交时检测功能
    preCompile=false //是否会编译文件，false时不会编译生成class文件，减少检测时间
}
```

## 4.
支持lint和pmd的相关extension，具体配置请参考相关文档

[lint](http://google.github.io/android-gradle-dsl/current/com.android.build.gradle.internal.dsl.LintOptions.html#com.android.build.gradle.internal.dsl.LintOptions)

[pmd](https://docs.gradle.org/current/dsl/org.gradle.api.plugins.quality.PmdExtension.html)
