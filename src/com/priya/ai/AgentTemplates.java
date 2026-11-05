package com.priya.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Built-in agent templates (plain Java). The six GrowthPilot Consulting agents are seeded OFF by AgentStore.seedTemplates();
 * the owner turns on the ones he wants. "Custom agent" is the blank starter for any other task.
 */
final class AgentTemplates {

    private AgentTemplates() {}

    static final String GP_EMAIL = "growthpilotconsulting1@gmail.com";
    static final String GP_SIGN = "Vivekanand Mandresh Yadav";
    static final String GP_WHATSAPP = "https://wa.me/message/IGO2PYHFIIWBD1";
    static final String GP_FORM = "https://docs.google.com/forms/d/e/1FAIpQLSe3q9KKg5HNUZqBcVW1NBEhGGBgyK54evsHOZAtDfdNptwWuw/viewform";
    static final String GP_TABLE = "growthpilot_leads";

    static final String GP_BRAND = "## GrowthPilot Consulting (one-person consultancy)\n"
            + "- Brand email (From account): " + GP_EMAIL + "\n"
            + "- Sign every email as: " + GP_SIGN + ", GrowthPilot Consulting (Data • Strategy • Growth)\n"
            + "- WhatsApp: " + GP_WHATSAPP + "\n"
            + "- Intake Google Form: " + GP_FORM + "\n"
            + "- Services: FREE Business Health Check ₹0 (24h) | Express Audit ₹999 (5–7 page PDF, 24–48h) | Standard Growth Audit ₹2,999 (20–30 pages, 2–3 days) | "
            + "Premium Growth Audit ₹7,999 (60–80 pages, 3–5 days) | Growth Partnership from ₹24,999/month | AI Automation services (free consultation, price later).\n"
            + "- Targets: clinics, salons, cafes, coaching institutes, gyms, hotels, retail, SMBs in Pune / Nagpur / Nashik / Solapur / Mumbai.\n"
            + "- Shared data table: '" + GP_TABLE + "' (columns: business, city, category, email, website, phone, package, status, notes). "
            + "Status flow: new → emailed → form_filled → health_check_sent / paid_order → work_done → payment_requested → delivered. "
            + "Stop statuses: bounced, unsubscribed, do_not_contact, not_interested (never contact again).\n"
            + "## Hard rules\n"
            + "- Kuch bhi delete mat karo — sirf status badlo ya 'archived' likho.\n"
            + "- Kabhi paisa kharch / payment / kharid nahi. Payment aaya ya nahi ye sirf owner batata hai.\n"
            + "- Email sirf table ke business contacts ko (public business email), kabhi personal logon ko ya table ke bahar nahi. Spam nahi: har email chhota, sahi aur personal.\n"
            + "- Paise maangne wala email (please pay / invoice / UPI) aur paid kaam ki FINAL delivery: pehle agent_request_approval, owner ki 'haan' ke baad hi.\n"
            + "- Email bhejne se pehle read_screen se From = " + GP_EMAIL + " pakka karo; alag ho to From par tap karke ye account chuno, na ho to ruk kar report.\n"
            + "- Password / OTP / API key kabhi mat likho ya bolo.\n";

    private static AgentStore.Agent base(String id, String name, String goal) {
        AgentStore.Agent a = new AgentStore.Agent();
        a.id = id;
        a.name = name;
        a.template = id;
        a.goal = goal;
        a.table = GP_TABLE;
        a.on = false;
        return a;
    }

    static List<AgentStore.Agent> growthPilot() {
        List<AgentStore.Agent> l = new ArrayList<>();

        AgentStore.Agent lf = base("gp_lead_finder", "GP Lead Finder",
                "# Lead Finder — naye business leads dhoondhna\n"
                        + "Goal: har run mein 10–15 naye, sahi business leads table '" + GP_TABLE + "' mein daalna.\n"
                        + "Steps:\n"
                        + "1. agent_note padho (context mein) — pichhli baar kaunsi city + category li thi; is baar agli lo (cities: Pune, Nagpur, Nashik, Solapur, Mumbai × categories: clinic, salon, cafe, coaching, gym, hotel, retail).\n"
                        + "2. research se dhoondho, jaise 'salons in Nashik with email contact', 'Google Maps cafes Pune website'. Zarurat ho to phone par Google Maps app/website kholo aur read_screen se naam, website, phone lo.\n"
                        + "3. Har business ki website web_fetch se kholo aur PUBLIC business email (contact page / footer) pakka karo. Email na mile to lead mat daalo (ya bina email ke status 'no_email').\n"
                        + "4. agent_table_upsert: {business, city, category, email, website, phone, source, status:'new'}. Duplicate apne aap SKIPPED hota hai; bounced / do_not_contact wale kabhi wapas nahi aate.\n"
                        + "5. agent_note: 'is run mein <city>/<category>, X naye, Y duplicate'.\n"
                        + "6. Report: kitne naye leads, kitne skip, kaunsi city/category.\n\n" + GP_BRAND);
        lf.tools = new ArrayList<>(Arrays.asList("phone"));
        lf.trigger = "daily";
        lf.time = "08:00";
        l.add(lf);

        AgentStore.Agent oe = base("gp_outreach", "GP Outreach Emailer",
                "# Outreach Emailer — free Health Check offer bhejna\n"
                        + "Goal: table ke status='new' leads ko chhota, personal email (roz max 20).\n"
                        + "Steps:\n"
                        + "1. agent_table_read status='new' limit 20.\n"
                        + "2. Har lead ke liye (website/category dekh kar ek personal line): subject jaise 'Free Business Health Check for <business>'. Body 70–120 shabd, English (ya simple Hinglish agar business Hindi mein ho): "
                        + "unke business ki ek sachchi tareef ya chhoti opportunity, FREE Business Health Check (₹0, 24 ghante mein report) ka offer, form link " + GP_FORM
                        + ", WhatsApp " + GP_WHATSAPP + ", sign-off '" + GP_SIGN + ", GrowthPilot Consulting'. Koi pressure / jhootha daawa nahi; aakhir mein ek line: 'Reply STOP if you prefer not to hear from us.'\n"
                        + "3. email_compose(to = lead ka email) → read_screen se From = " + GP_EMAIL + " aur To pakka → click_on_screen 'Send' → wait_for_text 'Sent|Sending'.\n"
                        + "4. Bheja gaya to agent_table_upsert {email, status:'emailed', emailed_on:<aaj ki date>}. Fail ho to status mat badlo, notes mein wajah.\n"
                        + "5. Limit / BLOCKED aaye to ruk jao. Report: kitne emails gaye (naam), kitne fail.\n\n" + GP_BRAND);
        oe.tools = new ArrayList<>(Arrays.asList("phone", "messages"));
        oe.recipientsFromTable = true;
        oe.dailyCap = 20;
        oe.trigger = "weekdays";
        oe.time = "10:00";
        l.add(oe);

        AgentStore.Agent ff = base("gp_form_followup", "GP Form Follow-up",
                "# Form Follow-up — form responses aur inbox dekhna\n"
                        + "Goal: jis business ne form bhara use thank-you, table update; bounce / STOP wale band.\n"
                        + "Steps:\n"
                        + "1. Gmail app kholo (account " + GP_EMAIL + "), search 'Google Forms OR \"new response\" OR GrowthPilot' — naye form response / reply padho (read_screen). Zarurat ho to form ka responses page open_link se.\n"
                        + "2. Har response se: business, naam, email, city, chuna hua package/service, zaroorat. agent_table_upsert {email, business, package, status:'form_filled', form_on:<date>, details}.\n"
                        + "3. Thank-you email (chhota): 'Thank you, form mil gaya. FREE Health Check report 24 ghante mein bhejenge' — paid package chuna ho to: 'aapka order note kar liya, scope aur timeline jaldi bhejenge'. Paise / payment ki baat is email mein NAHI.\n"
                        + "4. Inbox mein 'Delivery Status Notification', 'Address not found', 'Mail delivery failed' → us email ka status 'bounced'. Reply mein STOP / not interested → 'do_not_contact'. Positive reply → notes mein likho aur form link ke saath chhota jawab.\n"
                        + "5. Report: kitne form, kitne thank-you, kitne bounced / stop.\n\n" + GP_BRAND);
        ff.tools = new ArrayList<>(Arrays.asList("phone", "messages"));
        ff.recipientsFromTable = true;
        ff.dailyCap = 30;
        ff.trigger = "hourly";
        ff.everyHours = 3;
        ff.time = "09:00";
        l.add(ff);

        AgentStore.Agent hc = base("gp_health_check", "GP Free Health Check Builder",
                "# Free Business Health Check — report banana aur bhejna (FREE service, pre-approved)\n"
                        + "Goal: status='form_filled' aur package FREE / Health Check wale har lead ko 24 ghante mein report.\n"
                        + "Steps:\n"
                        + "1. agent_table_read status='form_filled'. Sirf FREE / Health Check wale lo (paid package wale Paid Order Worker ke hain).\n"
                        + "2. Research: unki website (web_fetch), Google listing / reviews, social media (research). Sirf sachchi, check ki hui baatein.\n"
                        + "3. generate_text (report, professional English): Business Health Score (/100 + wajah), website quick review, social media analysis, brand visibility, top 5 strengths, top 5 improvements, "
                        + "top 3 growth opportunities, 30-day quick wins, professional summary.\n"
                        + "4. agent_save_pdf(title '<business> - Business Health Check', text = report) — PDF phone par save.\n"
                        + "5. Email (email_compose → From check → Send → 'Sent'): chhota intro + poori report body mein + 'Agar detail chahiye to Express Audit (₹999, 5–7 page PDF) bhi available hai' + form/WhatsApp. Payment maangna NAHI.\n"
                        + "6. agent_table_upsert {email, status:'health_check_sent', report_path:<pdf path>, sent_on:<date>}.\n"
                        + "7. Report: kis business ko report gayi, PDF path.\n\n" + GP_BRAND);
        hc.tools = new ArrayList<>(Arrays.asList("phone", "messages"));
        hc.recipientsFromTable = true;
        hc.dailyCap = 10;
        hc.preApproved = "FREE Business Health Check report email (owner ne pehle se approve kiya)";
        hc.trigger = "daily";
        hc.time = "11:00";
        l.add(hc);

        AgentStore.Agent po = base("gp_paid_worker", "GP Paid Order Worker",
                "# Paid Order Worker — paid audit banana, payment request, final delivery\n"
                        + "Goal: paid orders ka kaam poora karna. Payment aaya ya nahi SIRF owner batata hai.\n"
                        + "Steps:\n"
                        + "A. Naya kaam: agent_table_read status='paid_order' (ya 'order_received'). Package ke hisaab se deliverable: Express Audit (5–7 pages: executive summary, website performance, brand identity, SEO overview, social + competitor snapshot, marketing review, health score, action plan); "
                        + "Standard (20–30 pages: SWOT, website/SEO/social/competitor audits, brand positioning, marketing + pricing review, 30-day plan); Premium (60–80 pages: full health assessment, competitor intelligence, SWOT+PESTLE, technical SEO, persona/journey, funnel, lead gen, revenue + cost optimisation, AI automation, risk matrix, 30/90/365 roadmaps); "
                        + "AI Automation / Partnership: proposal + plan. Research (web_fetch, research) → generate_text section-by-section → agent_save_pdf. agent_table_upsert {email, status:'work_done', report_path}.\n"
                        + "B. 'Work done, please pay' email: ye paise wali baat hai — pehle agent_request_approval(kind='money', to=email, summary='<business>: <package> ready. \"Work done, please pay ₹<amount>\" email bhejun?'). "
                        + "Context mein APPROVED dikhe tabhi email bhejo (report / link is email mein NAHI), phir status 'payment_requested'.\n"
                        + "C. Final delivery: status='payment_requested' wale ke liye agent_request_approval(kind='final_paid_delivery', to=email, summary='<business> ka ₹<amount> payment mila? Haan ho to final report bhejti hoon.'). "
                        + "Sirf APPROVED ke baad: email_compose (report ka saar + 'full report attached'), Gmail mein 'Attach file' se Documents/Priya/Agents wali PDF lagao (na lage to report body mein aur owner ko batao), Send, phir status 'delivered'.\n"
                        + "D. BLOCKED / APPROVAL_NEEDED aaye to bypass mat karo — request karke report do. Report: kya bana, kya approval ke liye ruka hai.\n\n" + GP_BRAND);
        po.tools = new ArrayList<>(Arrays.asList("phone", "messages"));
        po.recipientsFromTable = true;
        po.paidWork = true;
        po.dailyCap = 10;
        po.trigger = "daily";
        po.time = "12:00";
        l.add(po);

        AgentStore.Agent pm = base("gp_pipeline", "GP Daily Pipeline Manager",
                "# Daily Pipeline Manager — poori GrowthPilot pipeline roz\n"
                        + "Goal: in agents ko kram se chalana aur owner ko subah ki report.\n"
                        + "Steps:\n"
                        + "1. agent_handoff('gp_lead_finder', 'aaj ke naye leads').\n"
                        + "2. agent_handoff('gp_outreach', 'new leads ko outreach, cap ke andar').\n"
                        + "3. agent_handoff('gp_form_followup', 'form responses + inbox').\n"
                        + "4. agent_handoff('gp_health_check', 'form_filled FREE leads ki report').\n"
                        + "5. agent_handoff('gp_paid_worker', 'paid orders + approved payment/delivery').\n"
                        + "Koi agent OFF / fail ho to aage badho, report mein likho.\n"
                        + "6. agent_table_read (sirf ginti) aur notify_owner('GrowthPilot morning report', 5–7 lines: naye leads, aaj ke emails, forms, health checks, paid kaam, owner ki haan ke liye pending, dikkatein).\n"
                        + "7. Wahi report final jawab mein.\n\n" + GP_BRAND);
        pm.tools = new ArrayList<>();
        pm.handoffs = new ArrayList<>(Arrays.asList("gp_lead_finder", "gp_outreach", "gp_form_followup", "gp_health_check", "gp_paid_worker"));
        pm.trigger = "daily";
        pm.time = "08:30";
        l.add(pm);
        return l;
    }

    /** Blank starter for any task (UI "Naya agent" and create_agent without details). */
    static AgentStore.Agent custom() {
        AgentStore.Agent a = new AgentStore.Agent();
        a.name = "Custom agent";
        a.template = "custom";
        a.goal = "# Goal\n(kya kaam karna hai, kiske liye, output kya)\n\n## Steps\n1. ...\n2. ...\n\n## Rules\n- Kuch delete nahi, paisa kharch nahi, sirf allowed logon ko message.\n\n## Report\nAakhir mein 2–3 line: kya hua, kya bacha.";
        a.trigger = "manual";
        return a;
    }
}
