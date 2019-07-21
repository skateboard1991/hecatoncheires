package com.skateboard.hecatoncheires

class Constants {
    companion object {

        val GOUP_NAME = "increment"

        val INCREMENT_LINT_PREFIX = "incrementlint"

        val PMD = "pmd"

        val PMD_CONFIGURATION = "pmd"

        val PMDTASK = "pmdcheck"

        val P3C_PMD_DEPENDENCY = "com.alibaba.p3c:p3c-pmd:1.3.6"

        val PMD_DEPENDENCY = "net.sourceforge.pmd:pmd:5.5.2"

        val ALIRULESETS = mutableListOf<String>(
            "rulesets/java/ali-comment.xml", "rulesets/java/ali-concurrent.xml",
            "rulesets/java/ali-constant.xml", "rulesets/java/ali-exception.xml", "rulesets/java/ali-flowcontrol.xml",
            "rulesets/java/ali-naming.xml",
            "rulesets/java/ali-oop.xml",
            "rulesets/java/ali-orm.xml",
            "rulesets/java/ali-other.xml",
            "rulesets/java/ali-set.xml",
            "rulesets/vm/ali-other.xml"
        )

        val HECATONCHEIRESEXTENSION_NAME="hecatoncheires"
    }

}