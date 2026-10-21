package com.harbour.config

import com.harbour.domain.*
import com.harbour.domain.Unit as PropertyUnit
import com.harbour.repo.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

@Component
class SeedData(
    private val users:UserRepository,private val properties:PropertyRepository,private val units:UnitRepository,
    private val tenants:TenantRepository,private val leases:LeaseRepository,private val tickets:TicketRepository,
    private val comments:CommentRepository,private val payments:PaymentRepository,private val documents:DocumentRepository,
    private val inspections:InspectionRepository,private val messages:MessageRepository,private val notifications:NotificationRepository,
    private val audits:AuditRepository,private val integrations:IntegrationRepository,private val encoder:PasswordEncoder,
    @Value("\${portal.upload-dir:./data/uploads}") private val uploadDir:String
):ApplicationRunner{
    @Transactional override fun run(args:ApplicationArguments){
        if(users.count()>0)return
        val hash=encoder.encode("Harbour!2026")
        val admin=users.save(AppUser(email="admin@harbour.local",passwordHash=hash,displayName="Amelia Hart",role=Role.PROPERTY_ADMIN,phone="020 7946 0101"))
        val manager=users.save(AppUser(email="manager@harbour.local",passwordHash=hash,displayName="Marcus Webb",role=Role.PROPERTY_MANAGER,phone="020 7946 0102",buildingScope="MANAGED"))
        val worker=users.save(AppUser(email="worker@harbour.local",passwordHash=hash,displayName="Nia Okafor",role=Role.MAINTENANCE_WORKER,phone="07700 900123"))
        val tenantUsers=(1..25).map{n->users.save(AppUser(email=if(n==1)"tenant@harbour.local" else "tenant$n@harbour.local",passwordHash=hash,displayName=if(n==1)"Sofia Bennett" else "Resident ${n.toString().padStart(2,'0')}",role=Role.TENANT,phone="07700 91${n.toString().padStart(4,'0')}"))}
        val propertyNames=listOf("Alder Court","Bramley House","Canal Works","Dovecote Yard","Elmstead Place","Foundry Mews","Grove Terrace","Harbour Point")
        val props=propertyNames.mapIndexed{i,name->properties.save(Property(name=name,address="${12+i*7} ${listOf("Alder","Bramley","Canal","Dove","Elm","Foundry","Grove","Harbour")[i]} Street",buildingCode="BLD-${i+1}",city=if(i<6)"London" else "Bristol",managerId=manager.id))}
        val unitList=props.flatMapIndexed{pi,p->(1..4).map{n->units.save(PropertyUnit(propertyId=p.id,label="${(pi+1)*100+n}",bedrooms=(n%3)+1,occupied=(pi*4+n)<=25,marketRent=BigDecimal("${1250+pi*75+n*25}.00")))}}
        tenantUsers.forEachIndexed{i,u->tenants.save(TenantProfile(userId=u.id,emergencyContact="Mock Contact ${i+1} · 07700 800${i.toString().padStart(3,'0')}",householdSize=(i%4)+1,unitId=unitList[i].id))}
        val leaseList=tenantUsers.take(20).mapIndexed{i,u->
            val target=when(i%7){0->LeaseStatus.EXPIRED;1->LeaseStatus.RENEWAL_PENDING;else->LeaseStatus.ACTIVE}
            val lease=leases.save(Lease(tenantId=u.id,unitId=unitList[i].id,startDate=LocalDate.now().minusMonths((11+i%8).toLong()),endDate=if(target==LeaseStatus.EXPIRED)LocalDate.now().minusDays(20) else LocalDate.now().plusMonths((2+i%12).toLong()),rentAmount=unitList[i].marketRent,depositAmount=unitList[i].marketRent,status=LeaseStatus.DRAFT))
            lease.status=LeaseStatus.ACTIVE;leases.save(lease);if(target!=LeaseStatus.ACTIVE){lease.status=target;leases.save(lease)};audits.save(AuditEvent(actorId=admin.id,action="SEED_LEASE_WORKFLOW",resourceType="lease",resourceId=lease.id.toString(),afterValue="{\"status\":\"${lease.status}\"}",authorizationBasis="seeded-demo-workflow"));lease
        }
        leaseList.forEachIndexed{i,l->(0..2).forEach{m->val due=LocalDate.now().minusMonths(m.toLong());val overdue=m==0&&i%6==0;payments.save(Payment(leaseId=l.id,amount=l.rentAmount,dueDate=due,paidDate=if(overdue)null else due.plusDays(1),status=if(overdue)PaymentStatus.OVERDUE else PaymentStatus.PAID,reference="RENT-${i+1}-${due.monthValue}"))}}
        val titles=listOf("Kitchen tap is leaking","Hallway light flickers","Radiator stays cold","Intercom not working","Bathroom extractor noisy","Loose stair handrail","Dishwasher drain issue")
        val ticketList=(0 until 35).map{i->
            val tenant=tenantUsers[i%25];val t=tickets.save(MaintenanceTicket(tenantId=tenant.id,unitId=unitList[i%25].id,title=titles[i%titles.size],description="Mock resident report with practical access details for unit ${unitList[i%25].label}. Please contact the resident before attending.",category=listOf("Plumbing","Electrical","Heating","Safety")[i%4],priority=if(i%9==0)"URGENT" else "NORMAL",createdAt=Instant.now().minus(i.toLong(),ChronoUnit.DAYS),updatedAt=Instant.now().minus((i%5).toLong(),ChronoUnit.DAYS)))
            val target=listOf(TicketStatus.OPEN,TicketStatus.TRIAGED,TicketStatus.ASSIGNED,TicketStatus.IN_PROGRESS,TicketStatus.WAITING_ON_TENANT,TicketStatus.COMPLETED,TicketStatus.CLOSED)[i%7]
            val path=listOf(TicketStatus.OPEN,TicketStatus.TRIAGED,TicketStatus.ASSIGNED,TicketStatus.IN_PROGRESS,TicketStatus.COMPLETED,TicketStatus.CLOSED)
            if(target==TicketStatus.WAITING_ON_TENANT){t.status=TicketStatus.TRIAGED;t.status=TicketStatus.ASSIGNED;t.assignedWorkerId=worker.id;t.status=TicketStatus.IN_PROGRESS;t.status=TicketStatus.WAITING_ON_TENANT}
            else path.drop(1).takeWhile{path.indexOf(it)<=path.indexOf(target)}.forEach{s->t.status=s;if(s==TicketStatus.ASSIGNED)t.assignedWorkerId=worker.id}
            tickets.save(t);audits.save(AuditEvent(actorId=admin.id,action="SEED_WORKFLOW_ADVANCED",resourceType="ticket",resourceId=t.id.toString(),beforeValue="{}",afterValue="{\"status\":\"${t.status}\"}",authorizationBasis="seeded-demo-workflow"));t
        }
        ticketList.take(12).forEachIndexed{i,t->comments.save(TicketComment(ticketId=t.id,authorId=manager.id,body="We have reviewed the request and will coordinate next steps.",visibility=Visibility.TENANT));if(i%3==0)comments.save(TicketComment(ticketId=t.id,authorId=manager.id,body="Internal access planning note — never visible to the resident.",visibility=Visibility.INTERNAL))}
        val base=Path.of(uploadDir).toAbsolutePath().normalize();Files.createDirectories(base)
        (0 until 15).forEach{i->val stored="seed-${i+1}.txt";val body="Harbour mock ${if(i<10)"lease document" else "maintenance evidence"} ${i+1}. No real personal data.\n";Files.writeString(base.resolve(stored),body);documents.save(DocumentRecord(ownerId=admin.id,leaseId=if(i<10)leaseList[i%leaseList.size].id else null,ticketId=if(i>=10)ticketList[i].id else null,category=if(i<10)DocumentCategory.LEASE else DocumentCategory.MAINTENANCE_EVIDENCE,originalName=if(i<10)"Lease agreement v${i+1}.txt" else "Completion evidence ${i+1}.txt",storedName=stored,contentType="text/plain",sizeBytes=body.toByteArray().size.toLong(),tenantVisible=i%3!=0,versionNo=(i%2)+1))}
        (0 until 10).forEach{i->val target=when(i%5){0->InspectionStatus.SCHEDULED;1->InspectionStatus.IN_PROGRESS;2->InspectionStatus.COMPLETED;3->InspectionStatus.FOLLOW_UP_REQUIRED;else->InspectionStatus.CLOSED};val item=inspections.save(Inspection(unitId=unitList[i].id,scheduledFor=Instant.now().plus((i-3).toLong(),ChronoUnit.DAYS),status=InspectionStatus.SCHEDULED,findings=if(i>1)"Mock inspection: ventilation and alarms checked." else "",followUp=if(i%5==3)"Replace bathroom sealant within 14 days." else "",tenantVisible=i%2==0,createdBy=manager.id));if(target!=InspectionStatus.SCHEDULED){item.status=InspectionStatus.IN_PROGRESS;inspections.save(item)};if(target in setOf(InspectionStatus.COMPLETED,InspectionStatus.CLOSED)){item.status=InspectionStatus.COMPLETED;inspections.save(item)};if(target==InspectionStatus.FOLLOW_UP_REQUIRED){item.status=InspectionStatus.FOLLOW_UP_REQUIRED;inspections.save(item)};if(target==InspectionStatus.CLOSED){item.status=InspectionStatus.CLOSED;inspections.save(item)};audits.save(AuditEvent(actorId=manager.id,action="SEED_INSPECTION_WORKFLOW",resourceType="inspection",resourceId=item.id.toString(),afterValue="{\"status\":\"${item.status}\"}",authorizationBasis="seeded-demo-workflow"))}
        messages.save(MessageRecord(senderId=tenantUsers[0].id,recipientId=manager.id,subject="Delivery access next Tuesday",body="Could the concierge hold a parcel between 10 and 12?"));messages.save(MessageRecord(senderId=manager.id,recipientId=tenantUsers[0].id,subject="Re: Delivery access next Tuesday",body="Yes, I have let the concierge team know."));messages.save(MessageRecord(senderId=manager.id,recipientId=admin.id,subject="Renewal review",body="Internal note with portfolio renewal context.",internal=true))
        notifications.save(Notification(userId=tenantUsers[0].id,message="Your latest rent payment was recorded",link="/payments"));notifications.save(Notification(userId=worker.id,message="You have assigned maintenance work",link="/tickets"));notifications.save(Notification(userId=manager.id,message="Three tickets need triage",link="/tickets?status=OPEN"))
        integrations.save(IntegrationSetting(name="Local resident webhook",endpoint="http://localhost:8080/core-mock/events",enabled=false,secretHash="disabled-local-mock"))
        audits.save(AuditEvent(actorId=admin.id,action="DEMO_DATA_CREATED",resourceType="system",resourceId="seed",afterValue="{\"properties\":8,\"units\":32,\"tenants\":25}",authorizationBasis="application-bootstrap"))
    }
}
