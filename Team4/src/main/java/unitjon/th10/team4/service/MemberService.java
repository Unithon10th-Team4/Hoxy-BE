package unitjon.th10.team4.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import unitjon.th10.team4.config.SseEmitters;
import unitjon.th10.team4.dto.event.LocationUpdatedEvent;
import unitjon.th10.team4.dto.event.StatusUpdatedEvent;
import unitjon.th10.team4.dto.res.MemberUpdateResponse;
import unitjon.th10.team4.entity.Fanclub;
import unitjon.th10.team4.entity.Member;
import unitjon.th10.team4.entity.SseType;
import unitjon.th10.team4.repository.FanclubRepository;
import unitjon.th10.team4.repository.MemberRepository;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.StreamSupport;

@RequiredArgsConstructor
@Service
public class MemberService {
    private final StringRedisTemplate redisTemplate;

    private final MemberRepository memberRepository;
    private final S3Service s3Service;
    private final ApplicationEventPublisher publisher;
    private final FcmService fcmService;
    private final SseEmitters sseEmitters;
    private final FanclubRepository fanclubRepository;

    public List<Member> getAllMembers() {
        return StreamSupport.stream(memberRepository.findAll().spliterator(), false).toList();
    }

    public Member getMember(String name) {
        return memberRepository.findById(name).orElseThrow(() -> new RuntimeException("존재하지 않는 이름입니다."));
    }

    public void addMember(
            String name,
            String fanclubId,
            String fcmToken,
            Point location,
            MultipartFile profileImage
    ) {
        // 이미지 업로드
        String profileUrl = s3Service.saveImage(profileImage);

        // 이름 중복 확인
        if (memberRepository.existsById(name)) {
            throw new RuntimeException("이미 존재하는 이름입니다.");
        }

        // 멤버 생성 및 저장
        memberRepository.save(new Member(name, fanclubId, profileUrl, location, fcmToken));

        // 세션 갱신
        updateSessionExpiration(name);
    }

    private void updateSessionExpiration(String name) {
        String sessionKey = "member:%s:session".formatted(name);
        redisTemplate.opsForValue().set(sessionKey, "true");
        redisTemplate.expire(sessionKey, Duration.ofMinutes(5));
    }

    public List<Member> getNearMembers(String name, Point coordinates, int distance) {
        Member member = memberRepository.findById(name).orElseThrow(() -> new RuntimeException("존재하지 않는 이름입니다."));
        List<Member> nearMembers = memberRepository.findByLocationNear(coordinates, new Distance(distance, RedisGeoCommands.DistanceUnit.METERS));

        // 본인 제외 및 오프라인 제외
        nearMembers.removeIf(e -> e.getName().equals(member.getName()) || !e.isOnline());

        return nearMembers;
    }

    public void updateLocation(String name, Point point) {
        Member member = memberRepository.findById(name).orElseThrow(() -> new RuntimeException("존재하지 않는 이름입니다."));
        member.setLocation(point);
        memberRepository.save(member);

        // 세션 갱신
        updateSessionExpiration(name);

        publisher.publishEvent(new LocationUpdatedEvent(member));
    }

    public void updateStatus(String name, boolean isOnline) {
        Member member = memberRepository.findById(name).orElseThrow(() -> new RuntimeException("존재하지 않는 이름입니다."));
        member.setOnline(isOnline);
        memberRepository.save(member);
        publisher.publishEvent(new StatusUpdatedEvent(member));
    }

    @EventListener
    public void updateStatus(StatusUpdatedEvent event) throws IOException {
        Member updatedMember = event.member();
        List<Member> nearMembers = memberRepository.findByLocationNear(updatedMember.getLocation(), new Distance(2, RedisGeoCommands.DistanceUnit.METERS));
        nearMembers.removeIf(e -> !e.getFanclubId().equals(updatedMember.getFanclubId()) || !e.isOnline() || e.getName().equals(updatedMember.getName()));
        for (Member member : nearMembers) {
            String membername = member.getName();
            if (sseEmitters.existsMemberInSession(membername)) {
                SseEmitter sseEmitter = sseEmitters.get(membername);
                sseEmitter.send(
                        SseEmitter.event()
                                .name(updatedMember.getName())
                                .data(new MemberUpdateResponse.Status(
                                        updatedMember.getName(),
                                        updatedMember.isOnline(),
                                        SseType.STATUS)
                ));
            }
        }
    }

    @EventListener
    public void updateLocation(LocationUpdatedEvent event) {
        Member updatedMember = event.member();
        Fanclub fanclub = fanclubRepository.findById(updatedMember.getFanclubId()).orElseThrow(() -> new RuntimeException("존재하지 않는 팬클럽입니다."));
        List<Member> nearMembers = memberRepository.findByLocationNear(updatedMember.getLocation(), new Distance(2, RedisGeoCommands.DistanceUnit.METERS));

        // 동일한 팬클럽에 속한 오프라인 멤버만 추출 (본인 제외)
        nearMembers.removeIf(e -> !e.getFanclubId().equals(updatedMember.getFanclubId()) || e.isOnline() || e.getName().equals(updatedMember.getName()));

        // 최근 푸시 메시지 발송
        for (Member member : nearMembers) {
            String pushedDate = redisTemplate.opsForValue().get("member:%s:recently-pushed".formatted(member.getName()));
            boolean isRecentlyPushed = pushedDate != null && Instant.parse(pushedDate).isAfter(Instant.now().minusSeconds(3600));
            if (!isRecentlyPushed) {
                // 푸시 메시지 전송
                fcmService.sendMessageTo(
                        member.getFcmToken(),
                        "HOXY.. 내 옆에 %s가?".formatted(fanclub.getName()),
                        "%s님 주변에 %s가 있어요❤️".formatted(updatedMember.getName(), fanclub.getName()));

                // 푸시 메시지 수신 이력 저장
                redisTemplate.opsForValue().set("member:%s:recently-pushed".formatted(member.getName()), Instant.now().toString());
            }
        }
    }

    public void updatePoint(String name, int point) {
        Member member = memberRepository.findById(name).orElseThrow(() -> new RuntimeException("존재하지 않는 이름이다."));
        member.setPoint(member.getPoint() + point);
        memberRepository.save(member);
    }

    public String getFanclubIdByName(String name) {
        Member member = memberRepository.findById(name).orElseThrow(() -> new RuntimeException("존재하지 않는 이름이다."));
        return member.getFanclubId();
    }

    public void addDummyMemberByBasic(Member member){
        member.setOnline(true);
        memberRepository.save(member);
    }
}
