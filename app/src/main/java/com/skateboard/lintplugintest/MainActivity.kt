package com.skateboard.lintplugintest

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import java.util.*
import kotlin.properties.Delegates

class MainActivity : AppCompatActivity() {

    var name: String by Delegates.observable("<no name>") {
            prop, old, new ->
        println("$old -> $new")
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Toast.makeText(this,"",2)
        Arrays.sort(arrayOf("a")) { a, b ->
            if (a > b) 1 else -1
        }
    }
}

class Sentence(val words: List<String>)
class OtherSentence(private val words: List<String>): Iterable<String> by words

operator fun Sentence.iterator(): Iterator<String> = words.iterator()

