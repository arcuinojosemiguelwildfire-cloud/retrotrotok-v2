package com.example.ui.navigation

sealed class Screen {
    object Welcome : Screen()
    object Feed : Screen()
    object SongPicker : Screen()
    object DanceStudio : Screen()
    object VideoPreview : Screen()
    object AdminPin : Screen()
    object AdminDashboard : Screen()
    object HardwareValidation : Screen()
}
