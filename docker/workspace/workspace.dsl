workspace "Big Bank" {
    model {
        u = person "Customer"
        ss = softwareSystem "Internet Banking" {
            web = container "Web App" {
                c1 = component "Login Controller"
                c2 = component "Accounts Controller"
            }
            db = container "Database"
        }
        mail = softwareSystem "E-mail System"
        u -> web "Uses"
        c1 -> db "Reads"
        c2 -> db "Reads"
        web -> mail "Sends e-mail"
    }
    views {
        systemLandscape landscape {
            include *
            autolayout lr
        }
        systemContext ss context {
            include *
            autolayout lr
        }
        container ss containers {
            include *
            autolayout lr
        }
        component web components {
            include *
            autolayout lr
        }
    }
}
